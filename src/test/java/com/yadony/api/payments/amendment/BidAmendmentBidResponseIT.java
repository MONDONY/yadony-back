package com.yadony.api.payments.amendment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.dto.BidMoneyBreakdown;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.payments.cash.PaymentMethod;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Modifications exposées dans {@code BidResponse} (sur un vrai PostgreSQL) : liste des modifications, droit de
 * modifier, poids total et ventilation de l'argent, pour l'expéditeur et pour le voyageur, à l'unité et en liste.
 */
@SpringBootTest(properties = "yadony.bid-amendment.enabled=true")
@ActiveProfiles("e2e")
@DisplayName("BidResponse : modifications et ventilation de l'argent (PostgreSQL)")
class BidAmendmentBidResponseIT {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    /** Champs ajoutés par la modification : tous les autres doivent rester identiques. */
    private static final Set<String> NEW_FIELDS = Set.of("amendments", "amendmentAllowed", "totalWeightKg",
            "moneyBreakdown");

    @Autowired BidService bidService;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private UserEntity traveler;
    private UserEntity sender;
    private UUID annId;
    private UUID bidId;

    @BeforeEach
    void setUp() {
        traveler = persistUser();
        sender = persistUser();
        annId = persistAnnouncement();
        bidId = persistAcceptedBid(annId, PaymentMethod.STRIPE);
    }

    @Test
    @DisplayName("sans modification : liste vide, poids total = poids, pas de ventilation")
    void bidResponse_withoutAmendment_neutral() throws Exception {
        BidResponse forSender = bidService.getBidById(bidId, sender.getFirebaseUid());

        assertThat(forSender.amendments()).isEmpty();
        assertThat(forSender.totalWeightKg()).isEqualByComparingTo("5.40");
        assertThat(forSender.moneyBreakdown()).isNull();
        JsonNode json = objectMapper.valueToTree(forSender);
        assertThat(json.has("moneyBreakdown")).as("absent du JSON : l'app actuelle garde ses cartes").isFalse();
        assertThat(json.get("amendments").isArray()).isTrue();
    }

    @Test
    @DisplayName("modification acquise : les champs existants du JSON restent identiques, pour les deux parties")
    void bidResponse_securedAmendment_keepsExistingFieldsUnchanged() throws Exception {
        ObjectNode senderBefore = objectMapper.valueToTree(bidService.getBidById(bidId, sender.getFirebaseUid()));
        ObjectNode travelerBefore = objectMapper.valueToTree(bidService.getBidById(bidId, traveler.getFirebaseUid()));

        insertAmendment("CASH", "ACCEPTED", "3.00", "23.50", "3.30", "26.80", "CHARGED", now().minusHours(1));

        ObjectNode senderAfter = objectMapper.valueToTree(bidService.getBidById(bidId, sender.getFirebaseUid()));
        ObjectNode travelerAfter = objectMapper.valueToTree(bidService.getBidById(bidId, traveler.getFirebaseUid()));
        assertSameExceptNewFields(senderBefore, senderAfter);
        assertSameExceptNewFields(travelerBefore, travelerAfter);
        assertThat(senderAfter.get("weightKg").decimalValue()).isEqualByComparingTo("5.40");
        assertThat(senderAfter.get("totalWeightKg").decimalValue()).isEqualByComparingTo("8.40");
    }

    @Test
    @DisplayName("carte + espèces, expéditeur : bruts seulement, payé en ligne + à remettre, aucun net")
    void bidResponse_cardPlusCash_sender() throws Exception {
        UUID amendmentId = insertAmendment("CASH", "ACCEPTED", "3.00", "23.50", "3.30", "26.80", "CHARGED",
                now().minusHours(1));

        BidResponse r = bidService.getBidById(bidId, sender.getFirebaseUid());

        BigDecimal principalGross = r.totalSenderAmountEur();
        assertThat(principalGross).isNotNull();
        BidMoneyBreakdown b = r.moneyBreakdown();
        assertThat(b).isNotNull();
        assertThat(b.lines()).extracting(BidMoneyBreakdown.Line::kind).containsExactly("PRINCIPAL", "AMENDMENT");
        assertThat(b.lines().get(0).amount()).isEqualByComparingTo(principalGross);
        assertThat(b.lines().get(0).method()).isEqualTo("STRIPE");
        assertThat(b.lines().get(1).refId()).isEqualTo(amendmentId);
        assertThat(b.lines().get(1).amount()).isEqualByComparingTo("26.80");
        assertThat(b.onlineAmount()).isEqualByComparingTo(principalGross);
        assertThat(b.cashAmount()).isEqualByComparingTo("26.80");
        assertThat(b.total()).isEqualByComparingTo(principalGross.add(new BigDecimal("26.80")));
        assertThat(b.cashCommissionPaid()).isNull();
        assertThat(r.amendments()).hasSize(1);
        assertThat(r.amendments().get(0).netAmount()).isNull();
        assertThat(r.amendments().get(0).commissionAmount()).isNull();
        assertThat(r.totalNetAmountEur()).isNull();

        JsonNode json = objectMapper.valueToTree(r);
        assertThat(json.at("/moneyBreakdown").has("cashCommissionPaid")).isFalse();
        assertThat(json.at("/amendments/0").has("netAmount")).isFalse();
        assertThat(json.at("/amendments/0").has("commissionAmount")).isFalse();
        assertThat(json.at("/amendments/0").has("commissionStatus")).isFalse();
    }

    @Test
    @DisplayName("carte + espèces, voyageur : nets, espèces brutes encaissées et commission déjà réglée")
    void bidResponse_cardPlusCash_traveler() {
        insertAmendment("CASH", "ACCEPTED", "3.00", "23.50", "3.30", "26.80", "CHARGED", now().minusHours(1));

        BidResponse r = bidService.getBidById(bidId, traveler.getFirebaseUid());

        BigDecimal principalNet = r.totalNetAmountEur();
        assertThat(principalNet).isEqualByComparingTo("54.00");
        BidMoneyBreakdown b = r.moneyBreakdown();
        assertThat(b.lines().get(0).amount()).isEqualByComparingTo("54.00");
        assertThat(b.lines().get(1).amount()).isEqualByComparingTo("23.50");
        assertThat(b.onlineAmount()).isEqualByComparingTo("54.00");
        assertThat(b.cashAmount()).isEqualByComparingTo("26.80");
        assertThat(b.cashCommissionPaid()).isEqualByComparingTo("3.30");
        assertThat(b.total()).isEqualByComparingTo("77.50");
        assertThat(r.amendments().get(0).netAmount()).isEqualByComparingTo("23.50");
        assertThat(r.totalWeightKg()).isEqualByComparingTo("8.40");
    }

    @Test
    @DisplayName("espèces + carte, colis prélevé avant V348 : commission du colis initial retombée sur brut − net")
    void bidResponse_cashPrincipalPlusCard_traveler() {
        bidId = persistAcceptedBid(annId, PaymentMethod.CASH);
        jdbc.update("UPDATE bids SET commission_status = 'CHARGED' WHERE id = ?", bidId);
        insertAmendment("STRIPE", "ACCEPTED", "2.00", "20.00", "2.40", "22.40", null, now().minusHours(1));

        BidResponse forTraveler = bidService.getBidById(bidId, traveler.getFirebaseUid());
        BidResponse forSender = bidService.getBidById(bidId, sender.getFirebaseUid());

        BigDecimal principalGross = forSender.totalSenderAmountEur();
        BidMoneyBreakdown b = forTraveler.moneyBreakdown();
        assertThat(b.lines().get(0).method()).isEqualTo("CASH");
        assertThat(b.onlineAmount()).isEqualByComparingTo("20.00");
        assertThat(b.cashAmount()).isEqualByComparingTo(principalGross);
        assertThat(b.cashCommissionPaid()).isEqualByComparingTo(principalGross.subtract(new BigDecimal("54.00")));
        assertThat(b.total()).isEqualByComparingTo("74.00");
        assertThat(forSender.moneyBreakdown().onlineAmount()).isEqualByComparingTo("22.40");
        assertThat(forSender.moneyBreakdown().cashAmount()).isEqualByComparingTo(principalGross);
    }

    @Test
    @DisplayName("espèces avec bon de parrainage du voyageur : la commission réellement prélevée (moitié), pas brut − net")
    void bidResponse_cashPrincipalWithTravelerVoucher_readsChargedAmount() {
        bidId = persistAcceptedBid(annId, PaymentMethod.CASH);
        BigDecimal fullCommission = bidService.getBidById(bidId, sender.getFirebaseUid()).totalSenderAmountEur()
                .subtract(new BigDecimal("54.00"));
        BigDecimal halved = fullCommission.divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);
        jdbc.update("UPDATE bids SET commission_status = 'CHARGED', commission_charged_via = 'WALLET', "
                + "cash_commission_charged_amount = ? WHERE id = ?", halved, bidId);
        insertAmendment("CASH", "ACCEPTED", "3.00", "23.50", "3.30", "26.80", "CHARGED", now().minusHours(1));

        BidMoneyBreakdown b = bidService.getBidById(bidId, traveler.getFirebaseUid()).moneyBreakdown();

        assertThat(b.cashCommissionPaid()).isEqualByComparingTo(halved.add(new BigDecimal("3.30")));
        assertThat(b.cashCommissionPaid()).isNotEqualByComparingTo(fullCommission.add(new BigDecimal("3.30")));
    }

    @Test
    @DisplayName("espèces au plancher minimum (colis sans grille) : la commission prélevée fait foi")
    void bidResponse_cashPrincipalAtMinimumFloor_readsChargedAmount() {
        bidId = persistAcceptedBid(annId, PaymentMethod.CASH, new BigDecimal("0.50"));
        jdbc.update("UPDATE bids SET commission_status = 'CHARGED', commission_charged_via = 'WALLET', "
                + "cash_commission_charged_amount = 1.00 WHERE id = ?", bidId);
        insertAmendment("STRIPE", "ACCEPTED", "2.00", "20.00", "2.40", "22.40", null, now().minusHours(1));

        BidResponse forTraveler = bidService.getBidById(bidId, traveler.getFirebaseUid());

        assertThat(forTraveler.totalNetAmountEur()).isEqualByComparingTo("5.00");
        assertThat(forTraveler.moneyBreakdown().cashCommissionPaid()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("colis en grille pure (sans poids) : poids total = kilos acquis par modification, null sinon")
    void bidResponse_gridOnlyBid_totalWeightCountsSecuredKilos() {
        bidId = persistAcceptedBid(annId, PaymentMethod.STRIPE, null);
        assertThat(bidService.getBidById(bidId, sender.getFirebaseUid()).totalWeightKg()).isNull();

        insertAmendment("CASH", "ACCEPTED", "3.00", "23.50", "3.30", "26.80", "CHARGED", now().minusHours(1));

        BidResponse r = bidService.getBidById(bidId, sender.getFirebaseUid());
        assertThat(r.weightKg()).isNull();
        assertThat(r.totalWeightKg()).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("droit de modifier : vrai pour l'expéditeur d'un colis éligible, toujours faux pour le voyageur")
    void bidResponse_amendmentAllowed_falseForTraveler() {
        assertThat(bidService.getBidById(bidId, sender.getFirebaseUid()).amendmentAllowed()).isTrue();
        assertThat(bidService.getBidById(bidId, traveler.getFirebaseUid()).amendmentAllowed()).isFalse();
    }

    @Test
    @DisplayName("modification ouverte : ligne PENDING hors total, droit de modifier retiré")
    void bidResponse_openAmendment_pendingLine() {
        insertAmendment("CASH", "PROPOSED", "2.00", "20.00", "2.40", "22.40", null, now().minusMinutes(5));

        BidResponse forSender = bidService.getBidById(bidId, sender.getFirebaseUid());

        assertThat(forSender.amendmentAllowed()).isFalse();
        assertThat(forSender.moneyBreakdown().lines().get(1).status()).isEqualTo("PENDING");
        assertThat(forSender.moneyBreakdown().total()).isEqualByComparingTo(forSender.totalSenderAmountEur());
        assertThat(forSender.moneyBreakdown().cashAmount()).isEqualByComparingTo("0.00");
        assertThat(forSender.totalWeightKg()).as("kilos non acquis").isEqualByComparingTo("5.40");
        assertThat(forSender.amendments()).extracting(a -> a.status()).containsExactly("PROPOSED");
    }

    @Test
    @DisplayName("autorisation carte en attente : visible de l'expéditeur seul, le voyageur ne voit rien")
    void bidResponse_authorizationPending_hiddenFromTraveler() {
        insertAmendment("STRIPE", "AUTHORIZATION_PENDING", "2.00", "20.00", "2.40", "22.40", null,
                now().minusMinutes(5));

        BidResponse forTraveler = bidService.getBidById(bidId, traveler.getFirebaseUid());
        BidResponse forSender = bidService.getBidById(bidId, sender.getFirebaseUid());

        assertThat(forTraveler.amendments()).isEmpty();
        assertThat(forTraveler.moneyBreakdown()).isNull();
        assertThat(forSender.amendments()).hasSize(1);
        assertThat(forSender.moneyBreakdown().lines().get(1).status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("dernière modification close : montrée moins de 7 jours, sans ventilation ; plus ancienne, masquée")
    void bidResponse_closedAmendment_shownForSevenDays() {
        UUID old = insertAmendment("CASH", "DECLINED", "1.00", "10.00", "1.20", "11.20", null, now().minusDays(9));
        jdbc.update("UPDATE bid_amendments SET updated_at = ? WHERE id = ?",
                Timestamp.valueOf(now().minusDays(8)), old);

        assertThat(bidService.getBidById(bidId, sender.getFirebaseUid()).amendments()).isEmpty();

        UUID recent = insertAmendment("CASH", "EXPIRED", "1.00", "10.00", "1.20", "11.20", null, now().minusDays(2));
        BidResponse r = bidService.getBidById(bidId, sender.getFirebaseUid());
        assertThat(r.amendments()).extracting(a -> a.id()).containsExactly(recent);
        assertThat(r.moneyBreakdown()).isNull();
    }

    @Test
    @DisplayName("listes : mêmes champs qu'à l'unité (lectures groupées), expéditeur et voyageur")
    void lists_matchSingleRead() {
        UUID otherBid = persistAcceptedBid(annId, PaymentMethod.STRIPE);
        insertAmendment("CASH", "ACCEPTED", "3.00", "23.50", "3.30", "26.80", "CHARGED", now().minusHours(1));

        BidResponse single = bidService.getBidById(bidId, sender.getFirebaseUid());
        List<BidResponse> mine = bidService.getMyBids(sender.getFirebaseUid());
        BidResponse listed = mine.stream().filter(b -> b.id().equals(bidId)).findFirst().orElseThrow();
        BidResponse other = mine.stream().filter(b -> b.id().equals(otherBid)).findFirst().orElseThrow();
        assertThat(listed.moneyBreakdown()).isEqualTo(single.moneyBreakdown());
        assertThat(listed.amendments()).isEqualTo(single.amendments());
        assertThat(listed.totalWeightKg()).isEqualByComparingTo(single.totalWeightKg());
        assertThat(listed.amendmentAllowed()).isEqualTo(single.amendmentAllowed());
        assertThat(other.moneyBreakdown()).isNull();
        assertThat(other.amendmentAllowed()).isTrue();

        BidResponse travelerSingle = bidService.getBidById(bidId, traveler.getFirebaseUid());
        BidResponse travelerListed = bidService.getTravelerBids(traveler.getFirebaseUid(), null, null, null, 0, 20)
                .getContent().stream().filter(b -> b.id().equals(bidId)).findFirst().orElseThrow();
        assertThat(travelerListed.moneyBreakdown()).isEqualTo(travelerSingle.moneyBreakdown());
        assertThat(travelerListed.amendments()).isEqualTo(travelerSingle.amendments());
        assertThat(travelerListed.amendmentAllowed()).isFalse();
    }

    // ── Outils ───────────────────────────────────────────────────────────────

    private void assertSameExceptNewFields(ObjectNode before, ObjectNode after) {
        before.fieldNames().forEachRemaining(name -> {
            if (!NEW_FIELDS.contains(name)) {
                assertThat(after.get(name)).as(name).isEqualTo(before.get(name));
            }
        });
        after.fieldNames().forEachRemaining(name -> assertThat(before.has(name) || NEW_FIELDS.contains(name))
                .as("champ inattendu " + name).isTrue());
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private UUID insertAmendment(String rail, String status, String kg, String net, String commission, String gross,
                                 String commissionStatus, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        boolean secured = Set.of("ACCEPTED", "PAYOUT_PENDING", "PAID_OUT").contains(status);
        jdbc.update("""
                INSERT INTO bid_amendments (id, bid_id, announcement_id, traveler_id, sender_id, rail, status, currency,
                    added_weight_kg, capacity_reserved_kg, net_amount, commission_amount, gross_amount, expires_at,
                    accepted_at, commission_status, commission_charged_via, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'EUR', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, bidId, annId, traveler.getId(), sender.getId(), rail, status, new BigDecimal(kg),
                secured ? new BigDecimal(kg) : BigDecimal.ZERO, new BigDecimal(net), new BigDecimal(commission),
                new BigDecimal(gross), Timestamp.valueOf(createdAt.plusHours(48)),
                secured ? Timestamp.valueOf(createdAt.plusMinutes(10)) : null, commissionStatus,
                commissionStatus == null ? null : "WALLET", Timestamp.valueOf(createdAt), Timestamp.valueOf(createdAt));
        return id;
    }

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("amendment-view-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    /** Trajet à 10 €/kg, 6 kg libres, carte et espèces acceptées (le voyageur sans compte Connect : espèces seules). */
    private UUID persistAnnouncement() {
        return tx.execute(status -> {
            AnnouncementEntity announcement = new AnnouncementEntity();
            announcement.setTravelerId(traveler.getId());
            announcement.setDepartureCity("Paris");
            announcement.setArrivalCity("Dakar");
            announcement.setDepartureDate(LocalDate.now().plusDays(10));
            announcement.setTransportMode(TransportMode.PLANE);
            announcement.setPickupAddressLabel("Gare du Nord, Paris");
            announcement.setPickupLat(new BigDecimal("48.880756"));
            announcement.setPickupLng(new BigDecimal("2.354987"));
            announcement.setDeliveryAddressLabel("Aéroport de Dakar");
            announcement.setDeliveryLat(new BigDecimal("14.670833"));
            announcement.setDeliveryLng(new BigDecimal("-17.073056"));
            announcement.setAvailableKg(new BigDecimal("6.00"));
            announcement.setTotalKg(new BigDecimal("60.00"));
            announcement.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
            announcement.setPricePerKg(new BigDecimal("10.00"));
            announcement.setCurrency("EUR");
            announcement.setTimezone("Europe/Paris");
            announcement.setStatus(AnnouncementStatus.ACTIVE);
            announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH, PaymentMethod.STRIPE));
            entityManager.persist(announcement);
            return announcement.getId();
        });
    }

    private UUID persistAcceptedBid(UUID announcementId, PaymentMethod method) {
        return persistAcceptedBid(announcementId, method, new BigDecimal("5.40"));
    }

    private UUID persistAcceptedBid(UUID announcementId, PaymentMethod method, BigDecimal weightKg) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(sender.getId());
            bid.setPaymentMethod(method);
            bid.setCurrency("EUR");
            bid.setWeightKg(weightKg);
            bid.setCommissionRate(new BigDecimal("0.12"));
            bid.setStatus(BidStatus.ACCEPTED);
            bid.setQrToken("qr-" + UUID.randomUUID());
            bid.setTrackingNumber("DON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            entityManager.persist(bid);
            return bid.getId();
        });
    }
}
