package com.yadony.api.matching;

import com.yadony.api.config.PlatformSettingKey;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.CashCommissionService;
import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.mobilemoney.MobileMoneyBidPaymentService;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.requests.event.PackageRequestAcceptedEvent;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Règle de versement ({@code bids.payout_trigger}, V383) écrite par chacun des chemins qui font
 * passer un colis à ACCEPTED, sur PostgreSQL embarqué, toutes migrations Flyway et
 * {@code ddl-auto=validate} : le démarrage du contexte prouve que {@code payoutTrigger} et
 * {@code payoutTriggerFrozenAt} collent aux colonnes, chaque cas relit la ligne écrite.
 *
 * <p>Les chemins sont ceux recensés par {@code AcceptanceFreezesPayoutTriggerTest} : offre payée
 * par carte acceptée par le voyageur, la même acceptée par une automatisation, colis matérialisé
 * depuis un fil de négociation payé, colis en espèces accepté avec la commission, colis mobile
 * money dont le dépôt est confirmé.
 *
 * <p>Les trajets bateau sont au kilo : un trajet au m³ ne peut pas encore porter de colis (BB2).
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Règle de versement figée à l'acceptation (V383, Postgres, chaque chemin)")
class PayoutTriggerFrozenIT {

    private static EmbeddedPostgres postgres;

    /** Chaque chemin de code qui fait passer un colis à ACCEPTED. */
    enum AcceptancePath {
        /** {@code BidService.acceptBid} : offre directe payée par carte, acceptée par le voyageur. */
        CARD_OFFER,
        /** {@code BidService.acceptBidBySystem} : la même, acceptée par une règle d'automatisation. */
        AUTOMATION,
        /** {@code ThreadAcceptedBidListener} : colis né accepté d'un fil de négociation payé. */
        THREAD,
        /** {@code CashCommissionService.acceptCashBid} : espèces, commission prélevée au portefeuille. */
        CASH,
        /** {@code MobileMoneyBidPaymentService.confirmEscrow} : dépôt mobile money confirmé. */
        MOBILE_MONEY
    }

    @Autowired BidService bidService;
    @Autowired ThreadAcceptedBidListener threadAcceptedBidListener;
    @Autowired CashCommissionService cashCommissionService;
    @Autowired MobileMoneyBidPaymentService mobileMoneyBidPaymentService;
    @Autowired BidRepository bidRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired WalletAccountRepository walletAccountRepository;
    @Autowired PlatformSettingsService platformSettings;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) postgres.close();
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

    @AfterEach
    void closeBoat() {
        boat(false);
    }

    // ── Décor ────────────────────────────────────────────────────────────────

    private record Traveler(UUID id, String uid) {}

    private void boat(boolean enabled) {
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, String.valueOf(enabled)),
                UUID.randomUUID());
    }

    private Traveler traveler(boolean verifiedCarrier) {
        UUID id = UUID.randomUUID();
        String uid = "uid-payout-" + id;
        jdbc.update("""
                INSERT INTO users (id, firebase_uid, username, status, kyc_status, gp_pro, is_pro_account)
                VALUES (?, ?, ?, 'ACTIVE', 'VERIFIED', ?, false)
                """, id, uid, ("p" + id.toString().replace("-", "")).substring(0, 20), verifiedCarrier);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'TRAVELER')", id);
        return new Traveler(id, uid);
    }

    private UUID sender() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, firebase_uid, username, status, kyc_status)
                VALUES (?, ?, ?, 'ACTIVE', 'VERIFIED')
                """, id, "uid-payout-sender-" + id, ("s" + id.toString().replace("-", "")).substring(0, 20));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'SENDER')", id);
        return id;
    }

    /** Trajet au kilo (un trajet au m³ ne porte pas encore de colis), toutes méthodes de paiement. */
    private AnnouncementEntity trip(TransportMode mode, Traveler owner) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(owner.id());
        a.setDepartureCity("Marseille");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(mode);
        a.setPickupAddressLabel("Port de Marseille");
        a.setPickupLat(new BigDecimal("43.300000"));
        a.setPickupLng(new BigDecimal("5.370000"));
        a.setDeliveryAddressLabel("Port de Dakar");
        a.setDeliveryLat(new BigDecimal("14.690000"));
        a.setDeliveryLng(new BigDecimal("-17.440000"));
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setAvailableKg(new BigDecimal("200.00"));
        a.setTotalKg(new BigDecimal("200.00"));
        a.setPricePerKg(new BigDecimal("20.00"));
        a.setCurrency("EUR");
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY));
        return announcementRepository.saveAndFlush(a);
    }

    private BidEntity bid(AnnouncementEntity trip, PaymentMethod method, BidStatus status) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(trip.getId());
        b.setSenderId(sender());
        b.setWeightKg(new BigDecimal("5"));
        b.setDescription("Vêtements");
        b.setContentCategory("CLOTHING");
        b.setRecipientName("Aminata");
        b.setRecipientPhone("+221701234567");
        b.setCurrency("EUR");
        b.setPaymentMethod(method);
        b.setStatus(status);
        if (status == BidStatus.AWAITING_PAYMENT) {
            b.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30));
        }
        return bidRepository.saveAndFlush(b);
    }

    /** Fait passer un colis neuf à ACCEPTED par ce chemin, et rend son identifiant. */
    private UUID accept(AcceptancePath path, AnnouncementEntity trip, Traveler owner) {
        return switch (path) {
            case CARD_OFFER -> {
                BidEntity b = bid(trip, PaymentMethod.STRIPE, BidStatus.PAYMENT_ESCROWED);
                bidService.acceptBid(b.getId(), owner.uid());
                yield b.getId();
            }
            case AUTOMATION -> {
                BidEntity b = bid(trip, PaymentMethod.STRIPE, BidStatus.PAYMENT_ESCROWED);
                bidService.acceptBidBySystem(b.getId(), owner.id());
                yield b.getId();
            }
            case THREAD -> acceptThroughThread(trip, owner);
            case CASH -> {
                BidEntity b = bid(trip, PaymentMethod.CASH, BidStatus.PENDING);
                WalletAccountEntity wallet = new WalletAccountEntity();
                wallet.setUserId(owner.id());
                wallet.setCurrency("EUR");
                wallet.setBalance(new BigDecimal("500"));
                walletAccountRepository.saveAndFlush(wallet);
                assertThat(cashCommissionService.acceptCashBid(b.getId(), owner.id(), CommissionSource.WALLET_FIRST)
                        .status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
                yield b.getId();
            }
            case MOBILE_MONEY -> {
                BidEntity b = bid(trip, PaymentMethod.MOBILE_MONEY, BidStatus.AWAITING_PAYMENT);
                PaymentEntity p = new PaymentEntity();
                p.setBidId(b.getId());
                p.setRail(PaymentRail.PAWAPAY);
                p.setAmount(new BigDecimal("112.00"));
                p.setCommissionAmount(new BigDecimal("12.00"));
                p.setCurrency("EUR");
                p.setStatus(PaymentStatus.PENDING);
                p.setLegacyDestinationCharge(false);
                UUID paymentId = paymentRepository.saveAndFlush(p).getId();
                mobileMoneyBidPaymentService.confirmEscrow(UUID.randomUUID(), paymentId);
                yield b.getId();
            }
        };
    }

    private UUID acceptThroughThread(AnnouncementEntity trip, Traveler owner) {
        UUID senderId = sender();
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO package_requests (id, sender_id, departure_city, arrival_city, desired_date,
                    weight_kg, parcel_size, content_category, transport_mode, status)
                VALUES (?, ?, 'Marseille', 'Dakar', CURRENT_DATE + 10, 5, 'LARGE', 'CLOTHES', ?, 'OPEN')
                """, requestId, senderId, trip.getTransportMode().name());
        UUID threadId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO negotiation_threads (id, package_request_id, traveler_id, traveler_travel_date,
                    traveler_available_kg, status, current_price_eur, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_DATE + 10, 200, 'OPEN', 100.0, now(), now())
                """, threadId, requestId, owner.id());
        threadAcceptedBidListener.onPackageRequestAccepted(new PackageRequestAcceptedEvent(
                threadId, requestId, senderId, owner.id(), new BigDecimal("100.00"), trip.getId(),
                new BigDecimal("5"), "Vêtements", "CLOTHING", "pi_payout_" + threadId, "Awa", "+221771234567",
                LocalDateTime.now(ZoneOffset.UTC), "203.0.113.7", PaymentMethod.STRIPE, List.of(), null, null,
                null));
        return bidRepository.findByLinkedNegotiationThreadId(threadId).orElseThrow().getId();
    }

    private record Row(String status, String trigger, LocalDateTime frozenAt) {}

    private Row row(UUID bidId) {
        return jdbc.queryForObject(
                "SELECT status, payout_trigger, payout_trigger_frozen_at FROM bids WHERE id = ?",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toLocalDateTime()),
                bidId);
    }

    // ── Chaque chemin d'acceptation ─────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(AcceptancePath.class)
    @DisplayName("bateau, interrupteur allumé, transporteur vérifié : colis figé au dépôt")
    void acceptance_freezesDeposit(AcceptancePath path) {
        boat(true);
        Traveler owner = traveler(true);

        UUID bidId = accept(path, trip(TransportMode.BOAT, owner), owner);

        Row r = row(bidId);
        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(r.trigger()).isEqualTo("DEPOSIT");
        assertThat(r.frozenAt()).isNotNull();
        assertThat(bidRepository.findById(bidId).orElseThrow().getPayoutTrigger()).isEqualTo(PayoutTrigger.DEPOSIT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(AcceptancePath.class)
    @DisplayName("bateau, interrupteur éteint : colis figé à la livraison")
    void acceptance_boatSwitchedOff_freezesDelivery(AcceptancePath path) {
        boat(false);
        Traveler owner = traveler(true);

        Row r = row(accept(path, trip(TransportMode.BOAT, owner), owner));

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(r.trigger()).isEqualTo("DELIVERY");
        assertThat(r.frozenAt()).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(AcceptancePath.class)
    @DisplayName("avion, même transporteur vérifié et interrupteur allumé : colis figé à la livraison")
    void acceptance_planeTrip_freezesDelivery(AcceptancePath path) {
        boat(true);
        Traveler owner = traveler(true);

        Row r = row(accept(path, trip(TransportMode.PLANE, owner), owner));

        assertThat(r.trigger()).isEqualTo("DELIVERY");
        assertThat(r.frozenAt()).isNotNull();
    }

    @Test
    @DisplayName("bateau d'un transporteur seulement vérifié par KYC : colis figé à la livraison")
    void acceptance_unverifiedCarrier_freezesDelivery() {
        boat(true);
        Traveler owner = traveler(false);

        Row r = row(accept(AcceptancePath.CARD_OFFER, trip(TransportMode.BOAT, owner), owner));

        assertThat(r.trigger()).isEqualTo("DELIVERY");
    }

    // ── Jamais recalculée ───────────────────────────────────────────────────

    @Test
    @DisplayName("trajet passé à l'avion après l'acceptation : le colis reste au dépôt")
    void tripModeChangedAfterAcceptance_triggerUnchanged() {
        boat(true);
        Traveler owner = traveler(true);
        AnnouncementEntity trip = trip(TransportMode.BOAT, owner);
        UUID bidId = accept(AcceptancePath.CARD_OFFER, trip, owner);
        Row before = row(bidId);

        jdbc.update("UPDATE announcements SET transport_mode = 'PLANE' WHERE id = ?", trip.getId());

        assertThat(row(bidId)).isEqualTo(before);
        assertThat(row(bidId).trigger()).isEqualTo("DEPOSIT");
        BidResponse response = bidService.getBidById(bidId, owner.uid());
        assertThat(response.payoutTrigger()).isEqualTo("DEPOSIT");
    }

    @Test
    @DisplayName("interrupteur éteint et badge retiré après l'acceptation : le colis reste au dépôt")
    void boatSwitchedOffAfterAcceptance_triggerUnchanged() {
        boat(true);
        Traveler owner = traveler(true);
        UUID bidId = accept(AcceptancePath.MOBILE_MONEY, trip(TransportMode.BOAT, owner), owner);
        Row before = row(bidId);

        boat(false);
        jdbc.update("UPDATE users SET gp_pro = false WHERE id = ?", owner.id());

        assertThat(row(bidId)).isEqualTo(before);
        assertThat(row(bidId).trigger()).isEqualTo("DEPOSIT");
        assertThat(bidService.getBidById(bidId, owner.uid()).payoutTrigger()).isEqualTo("DEPOSIT");
    }

    @Test
    @DisplayName("colis remis en attente puis réaccepté : garde la règle de sa première acceptation")
    void reacceptance_keepsFirstFrozenRule() {
        boat(false);
        Traveler owner = traveler(true);
        AnnouncementEntity trip = trip(TransportMode.BOAT, owner);
        UUID bidId = accept(AcceptancePath.CARD_OFFER, trip, owner);
        Row first = row(bidId);
        assertThat(first.trigger()).isEqualTo("DELIVERY");

        // Remise en attente simulée (aucun chemin du code ne le fait aujourd'hui) puis bateau allumé :
        // la réacceptation dirait DEPOSIT, la règle figée à la première acceptation doit rester.
        jdbc.update("UPDATE bids SET status = 'PAYMENT_ESCROWED' WHERE id = ?", bidId);
        boat(true);
        bidService.acceptBid(bidId, owner.uid());

        Row second = row(bidId);
        assertThat(second.status()).isEqualTo("ACCEPTED");
        assertThat(second.trigger()).isEqualTo("DELIVERY");
        assertThat(second.frozenAt()).isEqualTo(first.frozenAt());
    }

    @Test
    @DisplayName("colis en attente d'acceptation : à la livraison, non figé, servi tel quel")
    void pendingBid_deliveryNotFrozen() {
        boat(true);
        Traveler owner = traveler(true);
        BidEntity b = bid(trip(TransportMode.BOAT, owner), PaymentMethod.STRIPE, BidStatus.PAYMENT_ESCROWED);

        Row r = row(b.getId());
        assertThat(r.trigger()).isEqualTo("DELIVERY");
        assertThat(r.frozenAt()).isNull();
        assertThat(bidService.getBidById(b.getId(), owner.uid()).payoutTrigger()).isEqualTo("DELIVERY");
    }

    // ── Schéma V383 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("V383 : défaut DELIVERY non figé, valeur inconnue et dépôt non figé refusés")
    void schema_defaultAndChecks() {
        Traveler owner = traveler(true);
        AnnouncementEntity trip = trip(TransportMode.BOAT, owner);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description, recipient_name,
                    recipient_phone, status, payment_method, currency, created_at, updated_at)
                VALUES (?, ?, ?, 5.00, 'Vêtements', 'Aminata', '+221701234567', 'PENDING', 'CASH', 'EUR',
                    now(), now())
                """, id, trip.getId(), sender());

        assertThat(row(id).trigger()).isEqualTo("DELIVERY");
        assertThat(row(id).frozenAt()).isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE bids SET payout_trigger = 'LATER' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bids_payout_trigger");
        assertThatThrownBy(() -> jdbc.update("UPDATE bids SET payout_trigger = 'DEPOSIT' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bids_payout_trigger_frozen");
        jdbc.update("UPDATE bids SET payout_trigger = 'DEPOSIT', payout_trigger_frozen_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)), id);
        assertThat(row(id).trigger()).isEqualTo("DEPOSIT");
    }
}
