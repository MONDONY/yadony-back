package com.yadony.api.admin;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La requête de liste des colis ({@link AdminBidSql}) sur PostgreSQL réel (migrations Flyway) :
 * {@link AdminBidSearchIT} couvre la sémantique sur H2, ce test garde la syntaxe et le typage des
 * paramètres liés à null honnêtes pour la production.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
class AdminBidSearchPostgresIT {

    private static final LocalDateTime NOW = LocalDateTime.now(ZoneOffset.UTC);

    private static EmbeddedPostgres postgres;

    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired JdbcTemplate jdbc;

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

    private UserEntity user(String firstName, String lastName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("admin-bid-search-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        return userRepository.saveAndFlush(u);
    }

    private AnnouncementEntity trip(UserEntity traveler) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(NOW.toLocalDate().minusDays(4));
        a.setArrivalDate(NOW.toLocalDate().minusDays(3));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport de Dakar");
        a.setDeliveryLat(new BigDecimal("14.670833"));
        a.setDeliveryLng(new BigDecimal("-17.073056"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("20.00"));
        a.setCurrency("EUR");
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
        return announcementRepository.saveAndFlush(a);
    }

    private BidEntity bid(AnnouncementEntity a, UserEntity sender, BidStatus status) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(sender.getId());
        b.setPaymentMethod(PaymentMethod.CASH);
        b.setCurrency("EUR");
        b.setWeightKg(new BigDecimal("3"));
        b.setStatus(status);
        b.setRecipientName("Ndèye Sow");
        b.setRecipientPhone("+221 77 123 45 67");
        b.setContentCategory("Vêtements");
        return bidRepository.saveAndFlush(b);
    }

    private List<UUID> ids(AdminBidQuery q) {
        return bidRepository.searchAdmin(q, PageRequest.of(0, 50)).map(BidEntity::getId).getContent();
    }

    private static AdminBidQuery query(AdminBidView view, String text, AdminBidScope scope, boolean phones) {
        return AdminBidQuery.of(view, null, null, text, scope, phones, null, null, null, null, null, NOW);
    }

    @Test
    void everyViewAndScope_runOnPostgres_withNullAndBoundParameters() {
        UserEntity traveler = user("Mamadou", "Kouyaté");
        UserEntity sender = user("Aïcha", "Diallo");
        AnnouncementEntity a = trip(traveler);
        BidEntity late = bid(a, sender, BidStatus.IN_TRANSIT);
        bid(a, sender, BidStatus.NEGOTIATING);

        for (AdminBidView view : AdminBidView.values()) {
            for (AdminBidScope scope : AdminBidScope.values()) {
                // Paramètres facultatifs tous à null, puis tous liés.
                assertThat(bidRepository.searchAdmin(query(view, null, scope, false), PageRequest.of(0, 5)))
                        .isNotNull();
                AdminBidQuery bound = AdminBidQuery.of(view, BidStatus.IN_TRANSIT, a.getId(), "aicha dakar",
                        scope, true, PaymentMethod.CASH, "vetement", AdminBidAlert.LATE,
                        NOW.minusDays(1), NOW.plusDays(1), NOW);
                assertThat(bidRepository.searchAdmin(bound, PageRequest.of(0, 5)).getTotalElements()).isNotNegative();
            }
        }

        assertThat(ids(AdminBidQuery.of(AdminBidView.ALL, BidStatus.IN_TRANSIT, a.getId(), "AÏCHA dakar",
                AdminBidScope.ALL, true, PaymentMethod.CASH, "VETEMENT", AdminBidAlert.LATE,
                NOW.minusDays(1), NOW.plusDays(1), NOW))).containsExactly(late.getId());
        assertThat(ids(query(AdminBidView.TODO, "kouyate", AdminBidScope.TRAVELER, false))).contains(late.getId());
        // Téléphone du destinataire : suffixe d'au moins huit chiffres, avec le droit seulement.
        assertThat(ids(query(AdminBidView.ALL, "+221771234567", AdminBidScope.RECIPIENT, true))).contains(late.getId());
        assertThat(ids(query(AdminBidView.ALL, "771234567", AdminBidScope.ALL, true))).contains(late.getId());
        assertThat(ids(query(AdminBidView.ALL, "771234567", AdminBidScope.RECIPIENT, false))).doesNotContain(late.getId());
        // R3 : quatre chiffres de tête non saisis sur douze (suffixe de 8) -> rien.
        assertThat(ids(query(AdminBidView.ALL, "71234567", AdminBidScope.RECIPIENT, true))).doesNotContain(late.getId());
        assertThat(ids(query(AdminBidView.ALL, "1234", AdminBidScope.RECIPIENT, true))).doesNotContain(late.getId());
        assertThat(ids(query(AdminBidView.ALL, "+22177123", AdminBidScope.RECIPIENT, true))).doesNotContain(late.getId());
        assertThat(ids(query(AdminBidView.ALL, "771234567", AdminBidScope.SENDER, true))).doesNotContain(late.getId());
    }

    @Test
    void idSearchAndAlertRanks_runOnPostgres() {
        UserEntity traveler = user("Mamadou", "Kouyaté");
        UserEntity sender = user("Aïcha", "Diallo");
        AnnouncementEntity a = trip(traveler);
        BidEntity noShow = bid(a, sender, BidStatus.NO_SHOW);
        BidEntity unpaid = bid(a, sender, BidStatus.AWAITING_PAYMENT);
        BidEntity calm = bid(a, sender, BidStatus.PENDING);
        jdbc.update("update bids set updated_at = ? where id = ?", NOW.minusHours(30), unpaid.getId());
        PaymentEntity payment = new PaymentEntity();
        payment.setBidId(noShow.getId());
        payment.setAmount(new BigDecimal("60.00"));
        payment.setCommissionAmount(new BigDecimal("3.00"));
        payment = paymentRepository.saveAndFlush(payment);
        DisputeEntity dispute = new DisputeEntity();
        dispute.setBidId(noShow.getId());
        dispute.setStatus("OPEN");
        dispute = disputeRepository.saveAndFlush(dispute);

        assertThat(ids(query(AdminBidView.ALL, noShow.getId().toString(), AdminBidScope.ALL, false)))
                .containsExactly(noShow.getId());
        assertThat(ids(query(AdminBidView.ALL, payment.getId().toString(), AdminBidScope.ALL, false)))
                .containsExactly(noShow.getId());
        assertThat(ids(query(AdminBidView.ALL, dispute.getId().toString(), AdminBidScope.ALL, false)))
                .containsExactly(noShow.getId());

        List<BidRepository.AlertRankRow> rows = bidRepository.alertRanks(
                List.of(noShow.getId(), unpaid.getId(), calm.getId()), NOW, AdminBidQuery.lateBefore(NOW));
        assertThat(rows).extracting(BidRepository.AlertRankRow::getBidId, BidRepository.AlertRankRow::getRank)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(noShow.getId(), 1),
                        org.assertj.core.groups.Tuple.tuple(unpaid.getId(), 3));
        assertThat(bidRepository.alertRanks(List.of(), NOW, AdminBidQuery.lateBefore(NOW))).isEmpty();
        assertThat(ids(query(AdminBidView.ALL, "%", AdminBidScope.ALL, false))).isEmpty();
        assertThat(ids(query(AdminBidView.TODO, null, AdminBidScope.ALL, false)))
                .containsSubsequence(noShow.getId(), unpaid.getId())
                .doesNotContain(calm.getId());

        // Nombres par vue (agrégation conditionnelle) sur PostgreSQL, restreints à ce trajet.
        BidRepository.AdminCountsRow counts = bidRepository.countAdmin(AdminBidQuery.of(null, null, a.getId(), null,
                AdminBidScope.ALL, true, null, null, null, null, null, NOW));
        assertThat(counts.getAll()).isEqualTo(3);
        assertThat(counts.getTodo()).isEqualTo(2);
        assertThat(counts.getDisputeOpen()).isEqualTo(1);
        assertThat(counts.getPaymentPending()).isEqualTo(1);
        assertThat(counts.getClosed()).isEqualTo(1);
        assertThat(counts.getActive()).isEqualTo(2);
        BidRepository.AdminCountsRow none = bidRepository.countAdmin(AdminBidQuery.of(null, null, UUID.randomUUID(),
                null, AdminBidScope.ALL, false, null, null, null, null, null, NOW));
        assertThat(none.getAll()).isZero();
        assertThat(none.getTodo()).isZero();
    }

    @Test
    void suggestions_runOnPostgres() {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UserEntity traveler = user("Mamadou", "Trav" + tag);
        UserEntity sender = user("Aïcha", "Send" + tag);
        UserEntity gone = user("Parti", "Send" + tag);
        AnnouncementEntity a = trip(traveler);
        bid(a, sender, BidStatus.IN_TRANSIT);
        bid(a, sender, BidStatus.PENDING);
        bid(a, gone, BidStatus.PENDING);
        bid(a, sender, BidStatus.NEGOTIATING);
        jdbc.update("update users set deleted_at = ? where id = ?", NOW, gone.getId());

        List<BidRepository.PersonCountRow> senders = bidRepository.suggestSenders("send" + tag, null, null, null);
        assertThat(senders).extracting(BidRepository.PersonCountRow::getId, BidRepository.PersonCountRow::getTotal)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(sender.getId(), 2L));
        assertThat(bidRepository.suggestSenders("aicha", "send" + tag, null, null)).hasSize(1);
        assertThat(bidRepository.suggestTravelers("trav" + tag, null, null, null))
                .extracting(BidRepository.PersonCountRow::getId).containsExactly(traveler.getId());
        assertThat(bidRepository.suggestRecipients("ndeye", "sow", null, null))
                .extracting(BidRepository.RecipientCountRow::getName).contains("Ndèye Sow");
        assertThat(bidRepository.suggestTrips("paris", "dakar", null, null)).isNotEmpty();
        assertThat(bidRepository.suggestTrips("paris", "absent" + tag, null, null)).isEmpty();
        assertThat(bidRepository.suggestParcels("yd", null, null, null)).hasSizeLessThanOrEqualTo(5);
        assertThat(bidRepository.suggestParcels("NOMATCH", null, null, null)).isEmpty();
    }
}
