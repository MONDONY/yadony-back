package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.integrity.MoneyIntegrityMonitor;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INV-18 (et les ajouts d'INV-03, 09, 17) sur un vrai PostgreSQL : chaque anomalie du plan §5.3
 * est injectée et détectée, et les états nominaux des trois rails ne sont jamais signalés (aucun
 * faux positif). Chaque test filtre les lignes en faute sur ses propres suppléments : les tests
 * partagent la base.
 */
@SpringBootTest(properties = {"yadony.bid-supplement.reconcile-enabled=false"})
@ActiveProfiles("e2e")
class BidSupplementMoneyInvariantsIT {

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

    @MockitoBean AdminAlertEscalator alerts;

    @Autowired MoneyIntegrityMonitor monitor;
    @Autowired BidSupplementRepository supplements;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    void etatsNominauxDesTroisRails_aucuneAnomalie() {
        Parties card = parties(PaymentMethod.STRIPE, BidStatus.ACCEPTED);
        UUID paid = supplement(card, BidSupplementRail.STRIPE, BidSupplementStatus.PAID, s -> {
            s.setStripePaymentIntentId("pi_" + UUID.randomUUID());
            s.setPaidAt(now());
        });
        Parties delivered = parties(PaymentMethod.STRIPE, BidStatus.COMPLETED);
        UUID paidOut = supplement(delivered, BidSupplementRail.STRIPE, BidSupplementStatus.PAID_OUT, s -> {
            s.setStripePaymentIntentId("pi_" + UUID.randomUUID());
            s.setStripeTransferId("tr_" + UUID.randomUUID());
            s.setPaidAt(now().minusDays(3));
            s.setPaidOutAt(now());
        });
        Parties mm = parties(PaymentMethod.MOBILE_MONEY, BidStatus.ACCEPTED);
        UUID mmPaid = supplement(mm, BidSupplementRail.PAWAPAY, BidSupplementStatus.PAID, s -> s.setPaidAt(now()));
        operation(mmPaid, mm.sender, "DEPOSIT", "COMPLETED", new BigDecimal("21.10"));
        Parties cash = parties(PaymentMethod.CASH, BidStatus.HANDED_OVER);
        UUID cashPaid = supplement(cash, BidSupplementRail.CASH, BidSupplementStatus.PAID, s -> {
            s.setCommissionChargedVia(CommissionChargedVia.CARD);
            s.setCommissionPaymentIntentId("pi_" + UUID.randomUUID());
            s.setCommissionStatus(CommissionStatus.CHARGED);
            s.setPaidAt(now());
        });
        // Demande espèces refusée il y a un instant : la commission est rendue par un écouteur
        // asynchrone, l'invariant laisse 1 h avant de la signaler.
        Parties cashDeclined = parties(PaymentMethod.CASH, BidStatus.ACCEPTED);
        UUID justDeclined = supplement(cashDeclined, BidSupplementRail.CASH, BidSupplementStatus.DECLINED, s -> {
            s.setCommissionChargedVia(CommissionChargedVia.WALLET);
            s.setCommissionStatus(CommissionStatus.CHARGED);
        });
        UUID debit = ledgerLine(cashDeclined.traveler, "SUPPLEMENT_COMMISSION_DEDUCTED", "-1.10",
                "supplement-" + justDeclined, cashDeclined.bidId);
        justNow(justDeclined, cashDeclined.bidId);

        List<Map<String, Object>> rows = rows("INV-18");
        assertThat(rows).noneMatch(r -> Set.of(paid, paidOut, mmPaid, cashPaid, justDeclined)
                .contains(UUID.fromString(String.valueOf(r.get("supplement_id")))));
        assertThat(rows("INV-03")).noneMatch(r -> debit.toString().equals(r.get("ref_id")));
        assertThat(rows("INV-09")).noneMatch(r -> String.valueOf(r.get("ref")).contains(justDeclined.toString()));
        assertThat(rows("INV-17")).noneMatch(r -> Set.of(paid, paidOut, mmPaid, cashPaid, justDeclined)
                .contains(UUID.fromString(String.valueOf(r.get("ref_id")))));
    }

    @Test
    void carteVerseeSansTransfert_detectee() {
        Parties p = parties(PaymentMethod.STRIPE, BidStatus.COMPLETED);
        UUID id = supplement(p, BidSupplementRail.STRIPE, BidSupplementStatus.PAID_OUT, s -> {
            s.setStripePaymentIntentId("pi_" + UUID.randomUUID());
            s.setPaidAt(now());
            s.setPaidOutAt(now());
        });

        assertThat(anomaly(id)).isEqualTo("SUPPLEMENT_VERSE_SANS_TRANSFERT");
    }

    @Test
    void payeSurUnColisMortDepuisPlusDUneHeure_detecte_maisPasDansLHeure() {
        Parties old = parties(PaymentMethod.STRIPE, BidStatus.CANCELLED);
        UUID stale = supplement(old, BidSupplementRail.STRIPE, BidSupplementStatus.PAID, s -> {
            s.setStripePaymentIntentId("pi_" + UUID.randomUUID());
            s.setPaidAt(now().minusDays(1));
        });
        jdbc.update("UPDATE bids SET updated_at = now() - interval '2 hours' WHERE id = ?", old.bidId);
        Parties fresh = parties(PaymentMethod.STRIPE, BidStatus.CANCELLED);
        UUID recent = supplement(fresh, BidSupplementRail.STRIPE, BidSupplementStatus.REFUND_PENDING, s -> {
            s.setStripePaymentIntentId("pi_" + UUID.randomUUID());
            s.setPaidAt(now());
        });
        justNow(recent, fresh.bidId);

        assertThat(anomaly(stale)).isEqualTo("SUPPLEMENT_PAYE_SUR_BID_MORT");
        assertThat(anomaly(recent)).as("remboursement en cours, dans l'heure").isNull();
    }

    @Test
    void commissionEspecesNonRendueSurDemandeClose_detectee() {
        Parties p = parties(PaymentMethod.CASH, BidStatus.ACCEPTED);
        UUID id = supplement(p, BidSupplementRail.CASH, BidSupplementStatus.EXPIRED, s -> {
            s.setCommissionChargedVia(CommissionChargedVia.CARD);
            s.setCommissionPaymentIntentId("pi_" + UUID.randomUUID());
            s.setCommissionStatus(CommissionStatus.CHARGED);
        });
        jdbc.update("UPDATE bid_supplements SET updated_at = (now() AT TIME ZONE 'UTC') - interval '2 hours' "
                + "WHERE id = ?", id);

        assertThat(anomaly(id)).isEqualTo("SUPPLEMENT_COMMISSION_NON_RENDUE");
    }

    @Test
    void commissionEspecesRembourseeSansRecredit_ouSansLigneDeDebit_detectees() {
        Parties p = parties(PaymentMethod.CASH, BidStatus.ACCEPTED);
        UUID refunded = supplement(p, BidSupplementRail.CASH, BidSupplementStatus.DECLINED, s -> {
            s.setCommissionChargedVia(CommissionChargedVia.WALLET);
            s.setCommissionStatus(CommissionStatus.REFUNDED);
        });
        ledgerLine(p.traveler, "SUPPLEMENT_COMMISSION_DEDUCTED", "-1.10", "supplement-" + refunded, p.bidId);
        Parties q = parties(PaymentMethod.CASH, BidStatus.ACCEPTED);
        UUID noLine = supplement(q, BidSupplementRail.CASH, BidSupplementStatus.PROPOSED, s -> {
            s.setCommissionChargedVia(CommissionChargedVia.WALLET);
            s.setCommissionStatus(CommissionStatus.CHARGED);
        });
        Parties r = parties(PaymentMethod.CASH, BidStatus.HANDED_OVER);
        UUID paidWithout = supplement(r, BidSupplementRail.CASH, BidSupplementStatus.PAID, s -> {
            s.setCommissionChargedVia(CommissionChargedVia.CARD);
            s.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
            s.setPaidAt(now());
        });

        assertThat(anomaly(refunded)).isEqualTo("SUPPLEMENT_COMMISSION_REMBOURSEE_SANS_RECREDIT");
        assertThat(anomaly(noLine)).isEqualTo("SUPPLEMENT_COMMISSION_SANS_LIGNE_LEDGER");
        assertThat(anomaly(paidWithout)).isEqualTo("SUPPLEMENT_ESPECES_SANS_COMMISSION");
    }

    @Test
    void commissionEspecesRemboursementEchoue_detecte() {
        Parties p = parties(PaymentMethod.CASH, BidStatus.ACCEPTED);
        UUID id = supplement(p, BidSupplementRail.CASH, BidSupplementStatus.WITHDRAWN, s -> {
            s.setCommissionChargedVia(CommissionChargedVia.CARD);
            s.setCommissionPaymentIntentId("pi_" + UUID.randomUUID());
            s.setCommissionStatus(CommissionStatus.REFUND_FAILED);
        });

        assertThat(anomaly(id)).isEqualTo("SUPPLEMENT_COMMISSION_REMBOURSEMENT_ECHOUE");
    }

    @Test
    void mobileMoney_verseEtRembourse_sansDepot_versementSansPayout_detectes() {
        Parties p = parties(PaymentMethod.MOBILE_MONEY, BidStatus.CANCELLED);
        UUID both = supplement(p, BidSupplementRail.PAWAPAY, BidSupplementStatus.REFUNDED, s -> s.setPaidAt(now()));
        operation(both, p.sender, "DEPOSIT", "COMPLETED", new BigDecimal("21.10"));
        operation(both, p.traveler, "PAYOUT", "COMPLETED", new BigDecimal("20.00"));
        operation(both, p.sender, "REFUND", "COMPLETED", new BigDecimal("21.10"));

        Parties q = parties(PaymentMethod.MOBILE_MONEY, BidStatus.ACCEPTED);
        UUID noDeposit = supplement(q, BidSupplementRail.PAWAPAY, BidSupplementStatus.PAID, s -> s.setPaidAt(now()));

        Parties r = parties(PaymentMethod.MOBILE_MONEY, BidStatus.COMPLETED);
        UUID noPayout = supplement(r, BidSupplementRail.PAWAPAY, BidSupplementStatus.PAID_OUT, s -> {
            s.setPaidAt(now());
            s.setPaidOutAt(now());
        });
        operation(noPayout, r.sender, "DEPOSIT", "COMPLETED", new BigDecimal("21.10"));

        Parties t = parties(PaymentMethod.MOBILE_MONEY, BidStatus.ACCEPTED);
        UUID wrongAmount = supplement(t, BidSupplementRail.PAWAPAY, BidSupplementStatus.PAID, s -> s.setPaidAt(now()));
        operation(wrongAmount, t.sender, "DEPOSIT", "COMPLETED", new BigDecimal("5.00"));

        assertThat(anomaly(both)).isEqualTo("SUPPLEMENT_MM_VERSE_ET_REMBOURSE");
        assertThat(anomaly(noDeposit)).isEqualTo("SUPPLEMENT_PAYE_SANS_ENCAISSEMENT");
        assertThat(anomaly(noPayout)).isEqualTo("SUPPLEMENT_MM_VERSE_SANS_PAYOUT");
        assertThat(anomaly(wrongAmount)).isEqualTo("SUPPLEMENT_DEPOT_MONTANT_DIFFERENT");
    }

    @Test
    void doublonsDeCommissionEtDeRecreditDuSupplement_inv09_signeNul_inv03() {
        Parties p = parties(PaymentMethod.CASH, BidStatus.ACCEPTED);
        UUID id = UUID.randomUUID();
        ledgerLine(p.traveler, "TOP_UP", "10.00", "pi_topup_" + id, null);
        ledgerLine(p.traveler, "SUPPLEMENT_COMMISSION_DEDUCTED", "-1.10", "supplement-" + id, p.bidId);
        ledgerLine(p.traveler, "SUPPLEMENT_COMMISSION_DEDUCTED", "-1.10", "supplement-" + id, p.bidId);
        ledgerLine(p.traveler, "REFUND", "1.10", "supplement-refund-" + id, p.bidId);
        ledgerLine(p.traveler, "REFUND", "1.10", "supplement-refund-" + id, p.bidId);
        UUID zero = UUID.randomUUID();
        UUID zeroLine = ledgerLine(p.traveler, "SUPPLEMENT_COMMISSION_DEDUCTED", "0.00", "supplement-" + zero, p.bidId);

        List<Map<String, Object>> inv09 = rows("INV-09");
        assertThat(inv09).anySatisfy(r -> {
            assertThat(r.get("kind")).isEqualTo("SUPPLEMENT_COMMISSION_DOUBLE");
            assertThat(r.get("ref")).isEqualTo("supplement-" + id + ":EUR");
        });
        assertThat(inv09).anySatisfy(r -> {
            assertThat(r.get("kind")).isEqualTo("SUPPLEMENT_RECREDIT_DOUBLE");
            assertThat(r.get("ref")).isEqualTo("supplement-refund-" + id + ":EUR");
        });
        assertThat(rows("INV-03")).anySatisfy(r -> {
            assertThat(r.get("anomaly")).isEqualTo("SIGNE_INCOHERENT_AVEC_TYPE");
            assertThat(r.get("ref_id")).isEqualTo(zeroLine.toString());
        });
    }

    @Test
    void lesDixHuitInvariantsTournentEtINV18SignaleLAlerte() {
        Parties p = parties(PaymentMethod.STRIPE, BidStatus.COMPLETED);
        supplement(p, BidSupplementRail.STRIPE, BidSupplementStatus.PAID_OUT, s -> {
            s.setStripePaymentIntentId("pi_" + UUID.randomUUID());
            s.setPaidAt(now());
            s.setPaidOutAt(now());
        });

        Map<String, Long> counts = monitor.runAll();

        assertThat(counts).containsKeys("INV-01", "INV-17", "INV-18");
        assertThat(counts.get("INV-18")).isPositive();
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private record Parties(UserEntity traveler, UserEntity sender, UUID bidId) {
    }

    private List<Map<String, Object>> rows(String code) {
        return monitor.inspect(code, MoneyIntegrityMonitor.MAX_INSPECT_ROWS).orElseThrow().rows();
    }

    /** Anomalie INV-18 du supplément, ou {@code null}. */
    private String anomaly(UUID supplementId) {
        return rows("INV-18").stream().filter(r -> supplementId.toString().equals(r.get("supplement_id")))
                .map(r -> String.valueOf(r.get("anomaly"))).findFirst().orElse(null);
    }

    /**
     * Horodate le supplément et le colis « à l'instant » côté base. Hibernate écrit un
     * {@code LocalDateTime} UTC comme une heure locale de la JVM : sur un poste en Europe/Paris, une
     * ligne fraîche paraît vieille de 1 ou 2 h aux requêtes SQL brutes de la sonde (en production la
     * JVM est en UTC, l'écart est nul).
     */
    private void justNow(UUID supplementId, UUID bidId) {
        jdbc.update("UPDATE bid_supplements SET updated_at = now() AT TIME ZONE 'UTC' WHERE id = ?", supplementId);
        jdbc.update("UPDATE bids SET updated_at = now() WHERE id = ?", bidId);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private UUID supplement(Parties p, BidSupplementRail rail, BidSupplementStatus status,
                            java.util.function.Consumer<BidSupplementEntity> customize) {
        return tx.execute(st -> {
            BidSupplementEntity s = new BidSupplementEntity();
            s.setBidId(p.bidId);
            s.setTravelerId(p.traveler.getId());
            s.setSenderId(p.sender.getId());
            s.setRail(rail);
            s.setStatus(status);
            s.setCurrency("EUR");
            s.setNetAmount(new BigDecimal("20.00"));
            s.setCommissionAmount(new BigDecimal("1.10"));
            s.setGrossAmount(new BigDecimal("21.10"));
            s.setReasonCode(BidSupplementReason.EXTRA_WEIGHT);
            s.setPhotoKey("tracking/" + p.bidId + "/1_supplement.jpg");
            s.setExpiresAt(now().plusHours(1));
            customize.accept(s);
            return supplements.saveAndFlush(s).getId();
        });
    }

    private void operation(UUID supplementId, UserEntity user, String kind, String status, BigDecimal amount) {
        jdbc.update("INSERT INTO pawapay_operations (id, kind, status, amount, currency, provider, country, msisdn, "
                        + "msisdn_masked, purpose, user_id, bid_supplement_id, finalized_at) "
                        + "VALUES (?, ?, ?, ?, 'EUR', 'ORANGE_CIV', 'CI', 'chiffre', '07****01', 'BID_SUPPLEMENT', ?, ?, now())",
                UUID.randomUUID(), kind, status, amount, user.getId(), supplementId);
    }

    /** Ligne du grand livre écrite telle quelle (solde après fictif, positif). */
    private UUID ledgerLine(UserEntity user, String type, String amount, String paymentRef, UUID bidId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO wallet_transactions (id, user_id, currency, type, amount, balance_after, payment_ref, "
                        + "bid_id, idempotency_key, created_at) VALUES (?, ?, 'EUR', ?, ?, 10.00, ?, ?, ?, now())",
                id, user.getId(), type, new BigDecimal(amount), paymentRef, bidId, "it-" + id);
        return id;
    }

    private Parties parties(PaymentMethod method, BidStatus status) {
        UserEntity traveler = persistUser();
        UserEntity sender = persistUser();
        UUID bidId = tx.execute(st -> {
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
            announcement.setAvailableKg(new BigDecimal("20.00"));
            announcement.setTotalKg(new BigDecimal("23.00"));
            announcement.setPricePerKg(new BigDecimal("20.00"));
            announcement.setCurrency("EUR");
            announcement.setTimezone("Europe/Paris");
            announcement.setStatus(AnnouncementStatus.ACTIVE);
            announcement.setAcceptedPaymentMethods(EnumSet.of(method));
            entityManager.persist(announcement);
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcement.getId());
            bid.setSenderId(sender.getId());
            bid.setPaymentMethod(method);
            bid.setCurrency("EUR");
            bid.setWeightKg(new BigDecimal("2"));
            bid.setStatus(status);
            if (method == PaymentMethod.CASH) {
                bid.setCommissionStatus(CommissionStatus.CHARGED);
                bid.setCommissionChargedVia(CommissionChargedVia.CARD);
                bid.setCommissionPaymentIntentId("pi_principal_" + UUID.randomUUID());
            }
            bid.setQrToken("qr-" + UUID.randomUUID());
            bid.setTrackingNumber("DON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            entityManager.persist(bid);
            return bid.getId();
        });
        return new Parties(traveler, sender, bidId);
    }

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("supplement-inv-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }
}
