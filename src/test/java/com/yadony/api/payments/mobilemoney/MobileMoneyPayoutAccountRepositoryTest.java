package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entité, requêtes dérivées et requête native de verrou sur le schéma H2 généré des entités
 * (le profil test n'exécute pas Flyway).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MobileMoneyPayoutAccountRepositoryTest {

    @Autowired private MobileMoneyPayoutAccountRepository repository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private JdbcTemplate jdbc;

    private MobileMoneyPayoutAccountEntity account(UUID userId, String provider) {
        return repository.saveAndFlush(new MobileMoneyPayoutAccountEntity(
                userId, provider, "CI", "XOF", "+2250700000001", "+225 ** ** 01", Instant.parse("2026-03-04T10:11:12Z")));
    }

    @Test
    void liveAccounts_areListedOldestFirst_andScopedToTheirOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        MobileMoneyPayoutAccountEntity first = account(owner, "ORANGE_CIV");
        Thread.sleep(5);
        MobileMoneyPayoutAccountEntity second = account(owner, "MTN_MOMO_CIV");
        account(UUID.randomUUID(), "ORANGE_CIV");

        assertThat(repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(owner))
                .extracting(MobileMoneyPayoutAccountEntity::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(repository.findByIdAndUserIdAndDeletedAtIsNull(first.getId(), owner)).isPresent();
        assertThat(repository.findByIdAndUserIdAndDeletedAtIsNull(first.getId(), UUID.randomUUID())).isEmpty();
        assertThat(repository.findByUserIdAndProviderAndDeletedAtIsNull(owner, "MTN_MOMO_CIV")).contains(second);
    }

    @Test
    void aSoftDeletedAccount_disappearsFromEveryQuery() {
        UUID owner = UUID.randomUUID();
        MobileMoneyPayoutAccountEntity removed = account(owner, "ORANGE_CIV");
        removed.softDelete();
        repository.saveAndFlush(removed);

        assertThat(repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(owner)).isEmpty();
        assertThat(repository.findByIdAndUserIdAndDeletedAtIsNull(removed.getId(), owner)).isEmpty();
        assertThat(repository.findByUserIdAndProviderAndDeletedAtIsNull(owner, "ORANGE_CIV")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_money_payout_accounts WHERE user_id = ?",
                Integer.class, owner)).isEqualTo(1);
    }

    @Test
    void msisdn_isStoredEncrypted_andReadBackInClear() {
        UUID owner = UUID.randomUUID();
        MobileMoneyPayoutAccountEntity saved = account(owner, "ORANGE_CIV");
        saved.setMigrated(true);
        saved.setLegacyAcceptedProviders("ORANGE_CIV,MTN_MOMO_CIV");
        repository.saveAndFlush(saved);

        String raw = jdbc.queryForObject("SELECT msisdn FROM mobile_money_payout_accounts WHERE id = ?", String.class, saved.getId());
        assertThat(raw).isNotEqualTo("+2250700000001").doesNotContain("0700000001");

        var reloaded = repository.findByUserIdAndProviderAndDeletedAtIsNull(owner, "ORANGE_CIV").orElseThrow();
        assertThat(reloaded.getMsisdn()).isEqualTo("+2250700000001");
        assertThat(reloaded.getMsisdnMasked()).isEqualTo("+225 ** ** 01");
        assertThat(reloaded.isMigrated()).isTrue();
        assertThat(reloaded.getLegacyAcceptedProviders()).isEqualTo("ORANGE_CIV,MTN_MOMO_CIV");
        assertThat(reloaded.getVerifiedAt()).isEqualTo(Instant.parse("2026-03-04T10:11:12Z"));
        assertThat(reloaded.getCountry()).isEqualTo("CI");
        assertThat(reloaded.getCurrency()).isEqualTo("XOF");
        assertThat(reloaded.getUserId()).isEqualTo(owner);
    }

    @Test
    void settersUpdateTheAccountInPlace() {
        UUID owner = UUID.randomUUID();
        MobileMoneyPayoutAccountEntity saved = account(owner, "ORANGE_CIV");
        saved.setProvider("ORANGE_SEN");
        saved.setCountry("SN");
        saved.setCurrency("XOF");
        saved.setMsisdn("+221770000002");
        saved.setMsisdnMasked("+221 ** ** 02");
        saved.setVerifiedAt(Instant.parse("2026-04-01T00:00:00Z"));
        repository.saveAndFlush(saved);

        var reloaded = repository.findByUserIdAndProviderAndDeletedAtIsNull(owner, "ORANGE_SEN").orElseThrow();
        assertThat(reloaded.getMsisdn()).isEqualTo("+221770000002");
        assertThat(reloaded.getCountry()).isEqualTo("SN");
    }

    // ── Verrou : colis payés sur un code qui attendent leur versement ─────────────────────────

    private static final String CIV = "ORANGE_CIV";

    /** Colis (bid) sur une annonce du voyageur ; retourne l'identifiant du bid. */
    private UUID parcel(UUID traveler) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Abidjan");
        a.setDepartureDate(LocalDate.now().plusDays(7));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Abidjan Plateau");
        a.setDeliveryLat(new BigDecimal("5.320000"));
        a.setDeliveryLng(new BigDecimal("-4.020000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a = announcementRepository.save(a);

        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(UUID.randomUUID());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(BidStatus.ACCEPTED);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+225 07 00 00 00 01");
        b.setTrackingNumber("DON-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode("123456");
        return bidRepository.saveAndFlush(b).getId();
    }

    private UUID thread(UUID traveler) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO negotiation_threads (id, package_request_id, traveler_id, traveler_travel_date, "
                + "traveler_available_kg, status, currency, current_price_eur, rounds_count, last_activity_at, "
                + "commission_retry_count, created_at, updated_at) VALUES (?, ?, ?, CURRENT_DATE, 10.00, 'OPEN', "
                + "'XOF', 20.00, 1, NOW(), 0, NOW(), NOW())", id, UUID.randomUUID(), traveler);
        return id;
    }

    private UUID payment(UUID bidId, UUID threadId, String rail, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, bid_id, negotiation_thread_id, rail, amount, currency, commission_amount, "
                + "status, legacy_destination_charge, disputed, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 15000, 'XOF', 1800, ?, false, false, NOW(), NOW())",
                id, bidId, threadId, rail, status);
        return id;
    }

    private UUID supplement(UUID bidId, UUID traveler, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bid_supplements (id, bid_id, traveler_id, sender_id, rail, status, currency, net_amount, "
                + "commission_amount, gross_amount, reason_code, photo_key, expires_at, stripe_pi_attempt, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, 'PAWAPAY', ?, 'XOF', 1000, 100, 1100, 'EXTRA_WEIGHT', 'k', NOW(), 0, "
                + "NOW(), NOW())", id, bidId, traveler, UUID.randomUUID(), status);
        return id;
    }

    private void operation(UUID paymentId, UUID supplementId, String kind, String status, String provider) {
        jdbc.update("INSERT INTO pawapay_operations (id, kind, status, amount, currency, provider, country, msisdn, "
                + "msisdn_masked, payment_id, purpose, topup_guard, bid_supplement_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, 15000, 'XOF', ?, 'CI', 'enc', '+225 ** 01', ?, ?, true, ?, NOW(), NOW())",
                UUID.randomUUID(), kind, status, provider, paymentId,
                supplementId == null ? "BID_PAYMENT" : "BID_SUPPLEMENT", supplementId);
    }

    /** Colis payé sur {@code provider} : paiement pawaPay du statut donné + dépôt du statut donné. */
    private UUID paidParcel(UUID traveler, String paymentStatus, String depositStatus, String provider) {
        UUID bid = parcel(traveler);
        operation(payment(bid, null, "PAWAPAY", paymentStatus), null, "DEPOSIT", depositStatus, provider);
        return bid;
    }

    @Test
    void lock_escrowWithCompletedDeposit_counts() {
        UUID traveler = UUID.randomUUID();
        paidParcel(traveler, "ESCROW", "COMPLETED", CIV);

        assertThat(repository.countParcelsAwaitingPayout(traveler, CIV)).isEqualTo(1);
        assertThat(repository.countParcelsAwaitingPayout(traveler, "orange_civ")).isEqualTo(1);
        assertThat(repository.countParcelsAwaitingPayout(UUID.randomUUID(), CIV)).isZero();
    }

    @Test
    void lock_completedPayout_unlocks_failedPayout_keepsTheLock() {
        UUID traveler = UUID.randomUUID();
        UUID paidOut = parcel(traveler);
        UUID p1 = payment(paidOut, null, "PAWAPAY", "RELEASED");
        operation(p1, null, "DEPOSIT", "COMPLETED", CIV);
        operation(p1, null, "PAYOUT", "COMPLETED", CIV);
        assertThat(repository.countParcelsAwaitingPayout(traveler, CIV)).isZero();

        UUID escrowPaidOut = parcel(traveler);
        UUID p2 = payment(escrowPaidOut, null, "PAWAPAY", "ESCROW");
        operation(p2, null, "DEPOSIT", "COMPLETED", CIV);
        operation(p2, null, "PAYOUT", "COMPLETED", CIV);
        assertThat(repository.countParcelsAwaitingPayout(traveler, CIV)).isZero();

        UUID failed = parcel(traveler);
        UUID p3 = payment(failed, null, "PAWAPAY", "RELEASED");
        operation(p3, null, "DEPOSIT", "COMPLETED", CIV);
        operation(p3, null, "PAYOUT", "FAILED", CIV);
        assertThat(repository.countParcelsAwaitingPayout(traveler, CIV)).isEqualTo(1);
    }

    @Test
    void lock_refundedOrStripePayment_doesNotCount() {
        UUID traveler = UUID.randomUUID();
        paidParcel(traveler, "REFUNDED", "COMPLETED", CIV);
        UUID stripe = parcel(traveler);
        operation(payment(stripe, null, "STRIPE", "ESCROW"), null, "DEPOSIT", "COMPLETED", CIV);

        assertThat(repository.countParcelsAwaitingPayout(traveler, CIV)).isZero();
    }

    @Test
    void lock_pendingPayment_countsWhileTheDepositIsAlive() {
        UUID traveler = UUID.randomUUID();
        paidParcel(traveler, "PENDING", "ACCEPTED", CIV);
        assertThat(repository.countParcelsAwaitingPayout(traveler, CIV)).isEqualTo(1);

        UUID other = UUID.randomUUID();
        paidParcel(other, "PENDING", "FAILED", CIV);
        paidParcel(other, "PENDING", "SUBMIT_REJECTED", CIV);
        assertThat(repository.countParcelsAwaitingPayout(other, CIV)).isZero();
    }

    @Test
    void lock_depositOnAnotherCode_doesNotLockThisOne() {
        UUID traveler = UUID.randomUUID();
        paidParcel(traveler, "ESCROW", "COMPLETED", "MTN_MOMO_CIV");

        assertThat(repository.countParcelsAwaitingPayout(traveler, CIV)).isZero();
        assertThat(repository.countParcelsAwaitingPayout(traveler, "MTN_MOMO_CIV")).isEqualTo(1);
    }

    @Test
    void lock_negotiationThreadPayment_isCounted() {
        UUID traveler = UUID.randomUUID();
        UUID thread = thread(traveler);
        operation(payment(null, thread, "PAWAPAY", "ESCROW"), null, "DEPOSIT", "COMPLETED", CIV);

        assertThat(repository.countParcelsAwaitingPayout(traveler, CIV)).isEqualTo(1);
        assertThat(repository.countParcelsAwaitingPayout(UUID.randomUUID(), CIV)).isZero();
    }

    @Test
    void lock_supplements_areCountedPerParcel() {
        UUID traveler = UUID.randomUUID();
        UUID counted = paidParcel(traveler, "ESCROW", "COMPLETED", CIV);
        operation(null, supplement(counted, traveler, "PAID"), "DEPOSIT", "COMPLETED", CIV);
        assertThat(repository.countParcelsAwaitingPayout(traveler, CIV)).as("même colis : compté une fois").isEqualTo(1);

        UUID otherParcel = parcel(traveler);
        operation(null, supplement(otherParcel, traveler, "PAYOUT_PENDING"), "DEPOSIT", "COMPLETED", CIV);
        assertThat(repository.countParcelsAwaitingPayout(traveler, CIV)).isEqualTo(2);

        UUID paidOutParcel = parcel(traveler);
        operation(null, supplement(paidOutParcel, traveler, "PAID_OUT"), "DEPOSIT", "COMPLETED", CIV);
        UUID failedDepositParcel = parcel(traveler);
        operation(null, supplement(failedDepositParcel, traveler, "PAYMENT_PENDING"), "DEPOSIT", "FAILED", CIV);
        assertThat(repository.countParcelsAwaitingPayout(traveler, CIV)).isEqualTo(2);
    }
}
