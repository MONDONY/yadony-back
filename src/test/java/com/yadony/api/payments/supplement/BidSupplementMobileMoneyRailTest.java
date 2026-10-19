package com.yadony.api.payments.supplement;

import com.yadony.api.payments.mobilemoney.MobileMoneyOperationViews;
import java.util.Map;
import com.yadony.api.payments.pawapay.PawapayValidationHints;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPayerProvidersResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPaymentStatusResponse;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.payments.supplement.BidSupplementPricingTest.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Rail mobile money du supplément : acceptation, initiation du dépôt, statut, catalogue, expiration. */
@ExtendWith(MockitoExtension.class)
class BidSupplementMobileMoneyRailTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String PAYER = "221770000001";

    @Mock BidSupplementMobileMoneyLedger ledger;
    @Mock BidSupplementRepository supplements;
    @Mock BidRepository bids;
    @Mock AnnouncementRepository announcements;
    @Mock UserRepository users;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock PawapayProviderResolver providers;
    @Mock StorageService storage;

    private UserEntity sender;
    private UserEntity traveler;
    private BidEntity bid;
    private BidSupplementEntity s;

    private BidSupplementMobileMoneyRail rail(boolean enabled) {
        return new BidSupplementMobileMoneyRail(ledger, supplements, bids, announcements, users, operations, submission,
                providers, BidSupplementMobileMoneyLedgerTest.pawapay(enabled), storage,
                new BidSupplementWindow(new BidSupplementProperties(), Clock.fixed(NOW, ZoneOffset.UTC)),
                new MobileMoneyOperationViews(TestMessages.resolver(), new PawapayValidationHints(Map.of())));
    }

    @BeforeEach
    void setUp() {
        sender = user("uid-sender");
        traveler = user("uid-traveler");
        traveler.setFirstName("Awa");
        traveler.setMobileMoneyProviderList(List.of("ORANGE_SEN", "WAVE_SEN"));
        AnnouncementEntity announcement = new AnnouncementEntity();
        setId(announcement, UUID.randomUUID());
        announcement.setTravelerId(traveler.getId());
        bid = new BidEntity();
        setId(bid, UUID.randomUUID());
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setMobileMoneyPhone(PAYER);
        s = new BidSupplementEntity();
        setId(s, UUID.randomUUID());
        s.setBidId(bid.getId());
        s.setSenderId(sender.getId());
        s.setTravelerId(traveler.getId());
        s.setRail(BidSupplementRail.PAWAPAY);
        s.setStatus(BidSupplementStatus.PAYMENT_PENDING);
        s.setCurrency("XOF");
        s.setNetAmount(new BigDecimal("13000"));
        s.setCommissionAmount(new BigDecimal("700"));
        s.setGrossAmount(new BigDecimal("13700"));
        s.setReasonCode(BidSupplementReason.GRID_ITEM);
        s.setPhotoKey("tracking/" + bid.getId() + "/1.jpg");
        s.setExpiresAt(NOW_UTC.plusHours(1));
        lenient().when(users.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        lenient().when(users.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        lenient().when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        lenient().when(bids.findById(bid.getId())).thenReturn(Optional.of(bid));
        lenient().when(announcements.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        lenient().when(supplements.findById(s.getId())).thenReturn(Optional.of(s));
        lenient().when(supplements.findByIdAndBidId(s.getId(), bid.getId())).thenReturn(Optional.of(s));
        lenient().when(ledger.prepareDeposit(bid.getId(), s.getId(), sender.getId()))
                .thenAnswer(inv -> claim(s.getStatus()));
    }

    private BidSupplementMobileMoneyLedger.Claim claim(BidSupplementStatus status) {
        return new BidSupplementMobileMoneyLedger.Claim(s.getId(), bid.getId(), sender.getId(), traveler.getId(),
                s.getNetAmount(), s.getGrossAmount(), "XOF", status, s.getExpiresAt());
    }

    private static UserEntity user(String uid) {
        UserEntity u = new UserEntity();
        setId(u, UUID.randomUUID());
        u.setFirebaseUid(uid);
        return u;
    }

    private static PawapayProviderResolver.Resolved resolved(String provider, String authType, String min, String max) {
        PawapayProviderConfig.Limits deposit = new PawapayProviderConfig.Limits(
                min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max), authType, null);
        return new PawapayProviderResolver.Resolved(provider, "SN", PAYER,
                new PawapayProviderConfig(provider, "SEN", "XOF", deposit, deposit));
    }

    private PawapayOperationEntity deposit(PawapayOperationStatus status) {
        PawapayOperationEntity op = PawapayOperationEntity.forSupplement(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                sender.getId(), s.getId(), null, s.getGrossAmount(), "XOF", "ORANGE_SEN", "SN", PAYER);
        op.setStatus(status);
        ReflectionTestUtils.setField(op, "createdAt", NOW_UTC.minusMinutes(5));
        return op;
    }

    // ── Acceptation ─────────────────────────────────────────────────────────

    @Test
    void acceptation_attenteDePaiementSansSecretCarte() {
        when(storage.generatePresignedUrl(any(), any())).thenReturn("https://signed");
        SupplementAcceptResponse r = rail(true).accept(s, sender);
        verify(ledger).startPayment(s);
        assertThat(r.rail()).isEqualTo("PAWAPAY");
        assertThat(r.clientSecret()).isNull();
        assertThat(r.amount()).isEqualByComparingTo("13700");
        assertThat(r.currency()).isEqualTo("XOF");
        assertThat(r.supplement().netAmount()).as("l'expéditeur ne voit pas le net").isNull();
        assertThat(rail(true).rail()).isEqualTo(BidSupplementRail.PAWAPAY);
    }

    // ── Initiation ───────────────────────────────────────────────────────────

    @Test
    void initiation_depotSupplement_pushPinSurLeNumeroQuiAPayeLeColis() {
        when(providers.resolve(eq(PAYER), eq(PawapayOperationKind.DEPOSIT), eq("XOF"), isNull(), anyString()))
                .thenReturn(resolved("ORANGE_SEN", "PROVIDER_AUTH", "100", "2000000"));
        PawapayOperationEntity op = deposit(PawapayOperationStatus.ACCEPTED);
        when(submission.submitSupplementDeposit(eq(s.getId()), eq(sender.getId()), eq(PAYER), eq("ORANGE_SEN"),
                eq("SN"), eq(new BigDecimal("13700")), eq("XOF"), eq("supp-" + s.getId()), isNull(), isNull()))
                .thenReturn(op);
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(operations.findLatestForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(op));

        MobileMoneyPaymentStatusResponse r = rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null);

        assertThat(r.bidId()).isEqualTo(bid.getId());
        assertThat(r.bidStatus()).isEqualTo("ACCEPTED");
        assertThat(r.paymentStatus()).isEqualTo("PENDING");
        assertThat(r.amount()).isEqualByComparingTo("13700");
        assertThat(r.deadlineAt()).isEqualTo(s.getExpiresAt());
        assertThat(r.deposit().id()).isEqualTo(op.getId());
        assertThat(r.deposit().status()).isEqualTo("ACCEPTED");
        verify(ledger).auditOnly(eq(s.getId()), eq("BID_SUPPLEMENT_MM_DEPOSIT_SUBMITTED"), anyMap());
    }

    @Test
    void initiation_redirectionWave_urlDeRetourDuSupplement() {
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(providers.resolve(eq("221780000009"), eq(PawapayOperationKind.DEPOSIT), eq("XOF"), eq("WAVE_SEN"),
                anyString())).thenReturn(resolved("WAVE_SEN", "REDIRECT_AUTH", null, null));
        String base = "https://api.yadony.com/api/v1/pawapay/return/" + bid.getId() + "?supplement=" + s.getId();
        when(submission.submitSupplementDeposit(any(), any(), any(), eq("WAVE_SEN"), any(), any(), any(), any(),
                eq(base + "&outcome=success"), eq(base + "&outcome=failed"))).thenReturn(deposit(PawapayOperationStatus.ACCEPTED));

        rail(true).initiate(bid.getId(), s.getId(), "uid-sender", "+221 78 000 00 09", "WAVE_SEN");

        verify(submission).submitSupplementDeposit(any(), any(), eq(PAYER), eq("WAVE_SEN"), any(), any(),
                any(), any(), eq(base + "&outcome=success"), eq(base + "&outcome=failed"));
    }

    @Test
    void initiation_compteMobileMoneyDeLExpediteur_prioritaire() {
        sender.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        sender.setMobileMoneyCurrency("XOF");
        sender.setMobileMoneyMsisdn("221770000777");
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(providers.resolve(eq("221770000777"), any(), any(), any(), anyString()))
                .thenReturn(resolved("ORANGE_SEN", "PROVIDER_AUTH", null, null));
        when(submission.submitSupplementDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(deposit(PawapayOperationStatus.ACCEPTED));

        rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null);

        verify(providers).resolve(eq("221770000777"), any(), any(), any(), anyString());
    }

    @Test
    void initiation_depotEnVol_reluSansNouveauDepot_memeRailCoupe() {
        PawapayOperationEntity live = deposit(PawapayOperationStatus.ACCEPTED);
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(live));
        when(operations.findLatestForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(live));

        MobileMoneyPaymentStatusResponse r = rail(false).initiate(bid.getId(), s.getId(), "uid-sender", null, null);

        assertThat(r.deposit().id()).isEqualTo(live.getId());
        verifyNoInteractions(submission, providers);
    }

    @Test
    void initiation_supplementDejaPaye_statutEscrow() {
        s.setStatus(BidSupplementStatus.PAID);
        MobileMoneyPaymentStatusResponse r = rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null);
        assertThat(r.paymentStatus()).isEqualTo("ESCROW");
        verifyNoInteractions(submission);
    }

    @Test
    void initiation_railCoupe_ouDemandeEchue_aucunDepot() {
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        assertCode(() -> rail(false).initiate(bid.getId(), s.getId(), "uid-sender", null, null), "mobile-money-disabled");
        s.setExpiresAt(NOW_UTC.minusSeconds(1));
        assertCode(() -> rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null), "supplement-expired");
        verifyNoInteractions(submission);
    }

    @Test
    void initiation_reseauNonAccepteParLeVoyageur_ouMontantHorsLimites_422() {
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(providers.resolve(any(), any(), any(), any(), anyString()))
                .thenReturn(resolved("MTN_MOMO_CIV", "PROVIDER_AUTH", null, null))
                .thenReturn(resolved("ORANGE_SEN", "PROVIDER_AUTH", "100", "5000"));
        assertCode(() -> rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null),
                "mobile-money-payer-unsupported");
        assertCode(() -> rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null),
                "mobile-money-amount-out-of-range");
        traveler.setMobileMoneyProviderList(List.of());
        when(providers.resolve(any(), any(), any(), any(), anyString()))
                .thenReturn(resolved("ORANGE_SEN", "PROVIDER_AUTH", null, null));
        assertCode(() -> rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null),
                "mobile-money-payer-unsupported");
        verifyNoInteractions(submission);
    }

    @Test
    void initiation_numeroInvalideOuInconnu_422() {
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        assertCode(() -> rail(true).initiate(bid.getId(), s.getId(), "uid-sender", "abc", null),
                "mobile-money-payer-unsupported");
        bid.setMobileMoneyPhone(null);
        assertCode(() -> rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null),
                "mobile-money-phone-required");
        bid.setMobileMoneyPhone(PAYER);
        for (PawapayProviderResolver.Reason reason : PawapayProviderResolver.Reason.values()) {
            doThrow(unsupported(reason)).when(providers).resolve(any(), any(), any(), any(), anyString());
            assertCode(() -> rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null),
                    "mobile-money-payer-unsupported");
        }
    }

    @Test
    void initiation_courseEntreDeuxInitiations_relitLeDepotGagnant() {
        PawapayOperationEntity winner = deposit(PawapayOperationStatus.ACCEPTED);
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(operations.findLatestForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(winner));
        when(providers.resolve(any(), any(), any(), any(), anyString()))
                .thenReturn(resolved("ORANGE_SEN", "PROVIDER_AUTH", null, null));
        when(submission.submitSupplementDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "mobile-money-operation-in-progress",
                        "In Progress", "déjà"));

        assertThat(rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null).deposit().id())
                .isEqualTo(winner.getId());
    }

    @Test
    void initiation_autreErreurDeSoumission_propagee_etRefusOperateur_422() {
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(providers.resolve(any(), any(), any(), any(), anyString()))
                .thenReturn(resolved("ORANGE_SEN", "PROVIDER_AUTH", null, null));
        PawapayOperationEntity rejected = deposit(PawapayOperationStatus.SUBMIT_REJECTED);
        rejected.setFailureCode("PAYER_LIMIT_REACHED");
        when(submission.submitSupplementDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "mobile-money-provider-unavailable",
                        "Down", "panne"))
                .thenReturn(rejected);

        assertCode(() -> rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null),
                "mobile-money-provider-unavailable");
        assertCode(() -> rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null),
                "mobile-money-deposit-rejected");
    }

    @Test
    void initiation_refusOperateur_detailTraduit() {
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(providers.resolve(any(), any(), any(), any(), anyString()))
                .thenReturn(resolved("ORANGE_SEN", "PROVIDER_AUTH", null, null));
        PawapayOperationEntity rejected = deposit(PawapayOperationStatus.SUBMIT_REJECTED);
        rejected.setFailureCode("PAYER_LIMIT_REACHED");
        rejected.setFailureMessage("Message brut pawaPay");
        when(submission.submitSupplementDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(rejected);

        assertThatThrownBy(() -> rail(true).initiate(bid.getId(), s.getId(), "uid-sender", null, null))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessage("Votre opérateur a refusé : plafond de paiement atteint.")
                .hasMessageNotContaining("Message brut");
    }

    @Test
    void statut_depotEchoue_exposeMotifTraduit() {
        PawapayOperationEntity failed = deposit(PawapayOperationStatus.FAILED);
        failed.setFailureCode("INSUFFICIENT_BALANCE");
        when(operations.findLatestForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(failed));

        var deposit = rail(true).status(bid.getId(), s.getId(), "uid-sender").deposit();

        assertThat(deposit.failureReasonCode()).isEqualTo("insufficient-balance");
        assertThat(deposit.failureReason()).isEqualTo("Solde insuffisant sur votre compte mobile money.");
        assertThat(deposit.failureMessage()).isEqualTo(deposit.failureReason());
        assertThat(deposit.failureCode()).isEqualTo("INSUFFICIENT_BALANCE");
    }

    // ── Statut ───────────────────────────────────────────────────────────────

    @Test
    void statut_expediteurEtVoyageur_seulement() {
        when(operations.findLatestForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        assertThat(rail(true).status(bid.getId(), s.getId(), "uid-sender").deposit()).isNull();
        assertThat(rail(true).status(bid.getId(), s.getId(), "uid-traveler").paymentStatus()).isEqualTo("PENDING");
        UserEntity other = user("uid-other");
        when(users.findByFirebaseUid("uid-other")).thenReturn(Optional.of(other));
        assertCode(() -> rail(true).status(bid.getId(), s.getId(), "uid-other"), "forbidden");
        assertCode(() -> rail(true).status(bid.getId(), s.getId(), "uid-unknown"), "user-not-found");
        UUID unknown = UUID.randomUUID();
        assertCode(() -> rail(true).status(bid.getId(), unknown, "uid-sender"), "supplement-not-found");
    }

    @Test
    void statut_vocabulaireDuPaiementMobileMoney() {
        assertThat(BidSupplementMobileMoneyRail.paymentStatus(BidSupplementStatus.PROPOSED)).isEqualTo("PENDING");
        assertThat(BidSupplementMobileMoneyRail.paymentStatus(BidSupplementStatus.COMMISSION_PENDING)).isEqualTo("PENDING");
        assertThat(BidSupplementMobileMoneyRail.paymentStatus(BidSupplementStatus.PAID)).isEqualTo("ESCROW");
        assertThat(BidSupplementMobileMoneyRail.paymentStatus(BidSupplementStatus.PAYOUT_PENDING)).isEqualTo("ESCROW");
        assertThat(BidSupplementMobileMoneyRail.paymentStatus(BidSupplementStatus.PAID_OUT)).isEqualTo("RELEASED");
        assertThat(BidSupplementMobileMoneyRail.paymentStatus(BidSupplementStatus.REFUND_PENDING))
                .as("remboursement en cours, jamais annoncé comme fait").isEqualTo("REFUND_PENDING");
        assertThat(BidSupplementMobileMoneyRail.paymentStatus(BidSupplementStatus.REFUNDED)).isEqualTo("REFUNDED");
        for (BidSupplementStatus closed : BidSupplementCardLedger.CLOSED_UNPAID) {
            assertThat(BidSupplementMobileMoneyRail.paymentStatus(closed)).isEqualTo("CANCELLED");
        }
    }

    @Test
    void statut_paiementTardifARendre_remboursementEnCours() {
        when(operations.findLatestForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        s.setStatus(BidSupplementStatus.DECLINED);
        assertThat(rail(true).status(bid.getId(), s.getId(), "uid-sender").paymentStatus()).isEqualTo("CANCELLED");
        s.setPaidAt(NOW_UTC);
        assertThat(rail(true).status(bid.getId(), s.getId(), "uid-sender").paymentStatus()).isEqualTo("REFUND_PENDING");
        s.setRefundedAt(NOW_UTC);
        s.setStatus(BidSupplementStatus.REFUNDED);
        assertThat(rail(true).status(bid.getId(), s.getId(), "uid-sender").paymentStatus()).isEqualTo("REFUNDED");
    }

    // ── Catalogue du payeur ──────────────────────────────────────────────────

    @Test
    void catalogue_reseauxDuPayeurAcceptesParLeVoyageur() {
        PawapayProviderConfig orange = resolved("ORANGE_SEN", "PROVIDER_AUTH", null, null).config();
        PawapayProviderConfig free = resolved("FREE_SEN", "PROVIDER_AUTH", null, null).config();
        when(providers.catalogue(eq(PAYER), eq(PawapayOperationKind.DEPOSIT), eq("XOF"), anyString()))
                .thenReturn(new PawapayProviderResolver.Catalogue("SN", "XOF", PAYER, "ORANGE_SEN", List.of(orange, free)));

        MobileMoneyPayerProvidersResponse r = rail(true).providersForPayer(bid.getId(), s.getId(), "uid-sender", null);

        assertThat(r.providers()).extracting(o -> o.code()).containsExactly("ORANGE_SEN");
        assertThat(r.detected()).isEqualTo("ORANGE_SEN");
        assertThat(r.travelerFirstName()).isEqualTo("Awa");
        assertThat(r.msisdnMasked()).doesNotContain("0000001");
    }

    @Test
    void catalogue_refus() {
        UserEntity other = user("uid-other");
        when(users.findByFirebaseUid("uid-other")).thenReturn(Optional.of(other));
        assertCode(() -> rail(true).providersForPayer(bid.getId(), s.getId(), "uid-other", null), "forbidden");
        assertCode(() -> rail(false).providersForPayer(bid.getId(), s.getId(), "uid-sender", null),
                "mobile-money-disabled");
        when(providers.catalogue(any(), any(), any(), anyString())).thenThrow(
                unsupported(PawapayProviderResolver.Reason.NO_PROVIDER));
        assertCode(() -> rail(true).providersForPayer(bid.getId(), s.getId(), "uid-sender", null),
                "mobile-money-payer-unsupported");
        s.setStatus(BidSupplementStatus.PAID);
        assertCode(() -> rail(true).providersForPayer(bid.getId(), s.getId(), "uid-sender", null),
                "supplement-not-pending");
        s.setRail(BidSupplementRail.STRIPE);
        assertCode(() -> rail(true).providersForPayer(bid.getId(), s.getId(), "uid-sender", null),
                "supplement-rail-mismatch");
    }

    // ── Expiration ───────────────────────────────────────────────────────────

    @Test
    void expiration_attendLIssueDuDepotDansLaLimiteDeLEcheanceDeDepot() {
        BidSupplementMobileMoneyRail rail = rail(true);
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        assertThat(rail.mayExpire(s)).as("aucun dépôt").isTrue();

        PawapayOperationEntity open = deposit(PawapayOperationStatus.ACCEPTED);
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(open));
        assertThat(rail.mayExpire(s)).as("dépôt ouvert récent").isFalse();

        ReflectionTestUtils.setField(open, "createdAt", NOW_UTC.minusMinutes(31));
        assertThat(rail.mayExpire(s)).as("dépôt ouvert au-delà de l'échéance de dépôt").isTrue();

        PawapayOperationEntity completed = deposit(PawapayOperationStatus.COMPLETED);
        ReflectionTestUtils.setField(completed, "createdAt", NOW_UTC.minusHours(2));
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(completed));
        assertThat(rail.mayExpire(s)).as("dépôt abouti en cours d'application").isFalse();
    }

    @Test
    void referenceClient() {
        assertThat(BidSupplementMobileMoneyRail.clientReference(s.getId())).isEqualTo("supp-" + s.getId());
        verify(ledger, never()).startPayment(any());
    }

    /** Constructeur réservé au paquet pawaPay : instancié par réflexion pour le test. */
    static PawapayProviderResolver.UnsupportedNumberException unsupported(PawapayProviderResolver.Reason reason) {
        try {
            var ctor = PawapayProviderResolver.UnsupportedNumberException.class.getDeclaredConstructor(
                    PawapayProviderResolver.Reason.class, String.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(reason, "ORANGE_SEN", "XAF");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
