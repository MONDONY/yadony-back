package com.yadony.api.payments.amendment;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V347 : table {@code bid_amendments} et ses lignes d'articles, 5e usage des opérations pawaPay et
 * débit de commission des modifications au grand livre. PostgreSQL embarqué avec le vrai Flyway et
 * {@code ddl-auto=validate} : les CHECK, les index partiels et le mapping JPA sont vérifiés pour de bon.
 * Les clés étrangères (colis, utilisateurs) ne sont pas l'objet du test : on les suspend dans la
 * transaction de chaque test (annulée en fin de test).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Schéma bid_amendments (V347)")
class BidAmendmentSchemaIT {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired BidAmendmentRepository amendments;
    @Autowired BidAmendmentItemRepository items;

    @Test
    @DisplayName("la migration s'applique, une seule modification ouverte par colis")
    void migrationApplies_andOneOpenPerBidEnforced() {
        jdbc.execute("SET LOCAL session_replication_role = replica");
        UUID bid = UUID.randomUUID();
        insert(bid, "STRIPE", "PROPOSED", null);

        // Une modification close ne bloque pas une nouvelle demande.
        assertThatCode(() -> insert(bid, "STRIPE", "DECLINED", null)).doesNotThrowAnyException();
        assertThatCode(() -> insert(UUID.randomUUID(), "STRIPE", "PROPOSED", null)).doesNotThrowAnyException();

        assertThatThrownBy(() -> insert(bid, "STRIPE", "PROPOSED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_bid_amendments_one_open");
    }

    @Test
    @DisplayName("chacun des quatre statuts ouverts occupe l'unique emplacement du colis, un statut conclu non")
    void everyOpenStatusHoldsTheSlot_concludedDoNot() {
        jdbc.execute("SET LOCAL session_replication_role = replica");
        for (String open : List.of("AUTHORIZATION_PENDING", "PROPOSED", "COMMISSION_PENDING", "PAYMENT_PENDING")) {
            UUID bid = UUID.randomUUID();
            insert(bid, "STRIPE", open, null);
            assertThatThrownBy(() -> insert(bid, "STRIPE", "PROPOSED", null))
                    .as(open)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_bid_amendments_one_open");
        }
        UUID bid = UUID.randomUUID();
        for (String concluded : List.of("ACCEPTED", "PAYOUT_PENDING", "PAID_OUT", "DECLINED", "EXPIRED", "CANCELLED",
                "REFUND_PENDING", "REFUNDED")) {
            insert(bid, "STRIPE", concluded, null);
        }
        assertThatCode(() -> insert(bid, "STRIPE", "PROPOSED", null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("une modification espèces n'a ni versement ni remboursement ni attente de paiement")
    void cashCannotBePayoutPending() {
        jdbc.execute("SET LOCAL session_replication_role = replica");
        for (String status : List.of("PAYMENT_PENDING", "PAYOUT_PENDING", "PAID_OUT", "REFUND_PENDING")) {
            UUID bid = UUID.randomUUID();
            assertThatThrownBy(() -> insert(bid, "CASH", status, null))
                    .as(status)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_bid_amendments_no_payout_cash");
        }
        assertThatCode(() -> insert(UUID.randomUUID(), "CASH", "ACCEPTED", null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("les champs carte sont réservés au rail STRIPE")
    void cardFieldsOnlyForStripe() {
        jdbc.execute("SET LOCAL session_replication_role = replica");
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "CASH", "PROPOSED", "pi_1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bid_amendments_card_fields");
        assertThatCode(() -> insert(UUID.randomUUID(), "STRIPE", "PROPOSED", "pi_2")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("un PaymentIntent ne peut servir qu'à une seule modification")
    void stripePaymentIntentIsUnique() {
        jdbc.execute("SET LOCAL session_replication_role = replica");
        insert(UUID.randomUUID(), "STRIPE", "PROPOSED", "pi_dup");
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "STRIPE", "PROPOSED", "pi_dup"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_bid_amendments_stripe_pi");
    }

    @Test
    @DisplayName("montants : brut = net + commission, net strictement positif")
    void amountsAreConsistent() {
        jdbc.execute("SET LOCAL session_replication_role = replica");
        assertThatThrownBy(() -> insertAmounts("10.00", "1.00", "12.00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bid_amendments_amounts");
    }

    @Test
    @DisplayName("une opération pawaPay BID_AMENDMENT exige l'identifiant de la modification")
    void pawapayPurposeRequiresAmendmentId() {
        jdbc.execute("SET LOCAL session_replication_role = replica");
        // Purpose BID_AMENDMENT sans identifiant : refusé.
        assertThatThrownBy(() -> pawapay("BID_AMENDMENT", "DEPOSIT", "ACCEPTED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_pawapay_ops_purpose_amendment");
    }

    @Test
    @DisplayName("un identifiant de modification sur un autre purpose est refusé")
    void amendmentIdRequiresPurpose() {
        jdbc.execute("SET LOCAL session_replication_role = replica");
        UUID amendment = insert(UUID.randomUUID(), "PAWAPAY", "PAYMENT_PENDING", null);

        assertThatThrownBy(() -> pawapay("WALLET_TOPUP", "DEPOSIT", "ACCEPTED", amendment))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_pawapay_ops_purpose_amendment");
    }

    @Test
    @DisplayName("une seule opération pawaPay vivante par (modification, type)")
    void onlyOneLivePawapayOperationPerKind() {
        jdbc.execute("SET LOCAL session_replication_role = replica");
        UUID amendment = insert(UUID.randomUUID(), "PAWAPAY", "PAYMENT_PENDING", null);

        pawapay("BID_AMENDMENT", "DEPOSIT", "ACCEPTED", amendment);
        // Un échec ne compte pas, un autre type est libre.
        assertThatCode(() -> pawapay("BID_AMENDMENT", "DEPOSIT", "FAILED", amendment)).doesNotThrowAnyException();
        assertThatCode(() -> pawapay("BID_AMENDMENT", "PAYOUT", "ACCEPTED", amendment)).doesNotThrowAnyException();

        assertThatThrownBy(() -> pawapay("BID_AMENDMENT", "DEPOSIT", "PROCESSING", amendment))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_pawapay_ops_live_per_amendment");
    }

    @Test
    @DisplayName("AMENDMENT_COMMISSION_DEDUCTED est un débit : accepté en négatif, refusé en positif")
    void walletTypeAccepted() {
        jdbc.execute("SET LOCAL session_replication_role = replica");
        assertThatCode(() -> wallet("AMENDMENT_COMMISSION_DEDUCTED", "-2")).doesNotThrowAnyException();

        assertThatThrownBy(() -> wallet("AMENDMENT_COMMISSION_DEDUCTED", "2"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_wallet_tx_sign_matches_type");
    }

    @Test
    @DisplayName("la somme des nets ne compte que les modifications acceptées et non remboursées du colis")
    void sumAcceptedNet_countsOnlyAcceptedNotRefunded() {
        jdbc.execute("SET LOCAL session_replication_role = replica");
        UUID bid = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        insertAccepted(bid, "ACCEPTED", "20.00", true);
        insertAccepted(bid, "PAID_OUT", "5.50", true);
        insertAccepted(bid, "PAYOUT_PENDING", "1.00", true);
        // Refusée ou remboursée : exclue, même acceptée un temps.
        insertAccepted(bid, "REFUNDED", "7.00", true);
        insertAccepted(bid, "REFUND_PENDING", "8.00", true);
        // Jamais acceptée : exclue.
        insertAccepted(bid, "DECLINED", "9.00", false);
        insertAccepted(bid, "PROPOSED", "11.00", false);
        // Autre colis.
        insertAccepted(other, "ACCEPTED", "3.00", true);

        assertThat(amendments.sumAcceptedNetByBidIdAndStatusNotIn(bid, BidAmendmentStatus.REFUNDED_STATES))
                .isEqualByComparingTo("26.50");
        assertThat(amendments.sumAcceptedNetByBidIdAndStatusNotIn(other, BidAmendmentStatus.REFUNDED_STATES))
                .isEqualByComparingTo("3.00");
        assertThat(amendments.sumAcceptedNetByBidIdAndStatusNotIn(UUID.randomUUID(), BidAmendmentStatus.REFUNDED_STATES))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(amendments.countByBidIdAndAcceptedAtNotNull(bid)).isEqualTo(5);
    }

    @Test
    @DisplayName("les entités se relisent telles qu'écrites (mapping validé contre le schéma)")
    void entitiesRoundTrip() {
        jdbc.execute("SET LOCAL session_replication_role = replica");
        UUID bidId = UUID.randomUUID();
        BidAmendmentEntity a = new BidAmendmentEntity();
        a.setBidId(bidId);
        a.setAnnouncementId(UUID.randomUUID());
        a.setTravelerId(UUID.randomUUID());
        a.setSenderId(UUID.randomUUID());
        a.setRail(com.yadony.api.payments.supplement.BidSupplementRail.STRIPE);
        a.setStatus(BidAmendmentStatus.AUTHORIZATION_PENDING);
        a.setCurrency("EUR");
        a.setAddedWeightKg(new BigDecimal("1.50"));
        a.setCapacityReservedKg(new BigDecimal("1.50"));
        a.setNetAmount(new BigDecimal("10.00"));
        a.setCommissionAmount(new BigDecimal("1.20"));
        a.setGrossAmount(new BigDecimal("11.20"));
        a.setExpiresAt(LocalDateTime.now().plusHours(48));
        a.setClosedReason(BidAmendmentClosedReason.EXPIRED);
        a.setStripePaymentIntentId("pi_round_trip");
        BidAmendmentEntity saved = amendments.saveAndFlush(a);

        BidAmendmentItemEntity item = new BidAmendmentItemEntity();
        item.setAmendmentId(saved.getId());
        item.setAnnouncementGridItemId(UUID.randomUUID());
        item.setLabelSnapshot("Valise cabine");
        item.setUnitPriceNetSnapshot(new BigDecimal("8.50"));
        item.setQuantity(2);
        items.saveAndFlush(item);

        BidAmendmentEntity read = amendments.findById(saved.getId()).orElseThrow();
        assertThat(read.getStatus()).isEqualTo(BidAmendmentStatus.AUTHORIZATION_PENDING);
        assertThat(read.getClosedReason()).isEqualTo(BidAmendmentClosedReason.EXPIRED);
        assertThat(read.getAddedWeightKg()).isEqualByComparingTo("1.50");
        assertThat(read.getVersion()).isNotNull();
        assertThat(amendments.findFirstByBidIdAndStatusIn(bidId, BidAmendmentStatus.OPEN)).isPresent();
        assertThat(amendments.findByBidIdOrderByCreatedAtDesc(bidId)).hasSize(1);
        assertThat(amendments.findByBidIdIn(List.of(bidId))).hasSize(1);
        assertThat(amendments.countByBidIdAndAcceptedAtNotNull(bidId)).isZero();
        assertThat(amendments.findByIdAndBidIdForUpdate(saved.getId(), bidId)).isPresent();
        assertThat(amendments.findIdsByStatusInAndExpiresAtBefore(
                BidAmendmentStatus.OPEN, LocalDateTime.now().plusDays(3))).containsExactly(saved.getId());
        assertThat(items.findByAmendmentId(saved.getId())).singleElement()
                .satisfies(i -> assertThat(i.getQuantity()).isEqualTo(2));
    }

    // ── Données ──────────────────────────────────────────────────────────────

    private UUID insert(UUID bidId, String rail, String status, String stripePi) {
        UUID id = UUID.randomUUID();
        String currency = "PAWAPAY".equals(rail) ? "XOF" : "EUR";
        guarded(() -> jdbc.update("""
                INSERT INTO bid_amendments (id, bid_id, announcement_id, traveler_id, sender_id, rail, status,
                    currency, net_amount, commission_amount, gross_amount, expires_at, stripe_payment_intent_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 10, 1, 11, now(), ?)
                """, id, bidId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), rail, status, currency,
                stripePi));
        return id;
    }

    /**
     * Un rejet de contrainte avorte la transaction PostgreSQL : un point de reprise la garde exploitable,
     * pour enchaîner plusieurs essais dans un même test.
     */
    private void guarded(Runnable statement) {
        jdbc.execute("SAVEPOINT guarded");
        try {
            statement.run();
            jdbc.execute("RELEASE SAVEPOINT guarded");
        } catch (DataIntegrityViolationException e) {
            jdbc.execute("ROLLBACK TO SAVEPOINT guarded");
            throw e;
        }
    }

    private void insertAccepted(UUID bidId, String status, String net, boolean accepted) {
        guarded(() -> jdbc.update("""
                INSERT INTO bid_amendments (id, bid_id, announcement_id, traveler_id, sender_id, rail, status,
                    currency, net_amount, commission_amount, gross_amount, expires_at, accepted_at)
                VALUES (?, ?, ?, ?, ?, 'STRIPE', ?, 'EUR', ?::numeric, 0, ?::numeric, now(),
                    CASE WHEN ? THEN now() END)
                """, UUID.randomUUID(), bidId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status, net,
                net, accepted));
    }

    private void insertAmounts(String net, String commission, String gross) {
        guarded(() -> jdbc.update("""
                INSERT INTO bid_amendments (id, bid_id, announcement_id, traveler_id, sender_id, rail, status,
                    currency, net_amount, commission_amount, gross_amount, expires_at)
                VALUES (?, ?, ?, ?, ?, 'STRIPE', 'PROPOSED', 'EUR', ?::numeric, ?::numeric, ?::numeric, now())
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                net, commission, gross));
    }

    private void pawapay(String purpose, String kind, String status, UUID amendmentId) {
        guarded(() -> jdbc.update("""
                INSERT INTO pawapay_operations (id, kind, status, amount, currency, provider, country, msisdn,
                    msisdn_masked, purpose, user_id, bid_amendment_id, created_at, updated_at)
                VALUES (?, ?, ?, 1100, 'XOF', 'ORANGE_SEN', 'SN', 'enc', '+221 •••• 67', ?, ?, ?, now(), now())
                """, UUID.randomUUID(), kind, status, purpose, UUID.randomUUID(), amendmentId));
    }

    private void wallet(String type, String amount) {
        guarded(() -> jdbc.update("""
                INSERT INTO wallet_transactions (id, user_id, currency, type, amount, balance_after)
                VALUES (?, ?, 'EUR', ?, ?::numeric, 10)
                """, UUID.randomUUID(), UUID.randomUUID(), type, amount));
    }
}
