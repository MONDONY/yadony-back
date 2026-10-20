package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccountEntity;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccountRepository;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccounts;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.payments.supplement.BidSupplementPricingTest.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Versement mobile money du supplément : même destination que le principal, gardes, idempotence. */
@ExtendWith(MockitoExtension.class)
class BidSupplementMobileMoneyPayoutsTest {

    @Mock BidSupplementMobileMoneyLedger ledger;
    @Mock BidSupplementRepository supplements;
    @Mock UserRepository users;
    @Mock MobileMoneyPayoutAccountRepository accountRepository;
    @Mock PaymentRepository payments;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock PayoutHoldPolicy holdPolicy;
    @Mock DisputeRepository disputes;
    @Mock AdminAlertEscalator alerts;
    @Mock com.yadony.api.payments.pawapay.PawapayClient client;

    private final BidSupplementProperties properties = new BidSupplementProperties();
    private BidSupplementMobileMoneyPayouts payouts;
    private BidSupplementEntity s;
    private UserEntity traveler;
    private PaymentEntity principal;
    private BidSupplementMobileMoneyLedger.Claim claim;

    @BeforeEach
    void setUp() {
        payouts = new BidSupplementMobileMoneyPayouts(ledger, supplements, users,
                new MobileMoneyPayoutAccounts(accountRepository), payments, operations, submission,
                holdPolicy, disputes, alerts, client, properties);
        lenient().when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", config("ORANGE_SEN", null, null),
                "WAVE_SEN", config("WAVE_SEN", null, null)));
        traveler = new UserEntity();
        setId(traveler, UUID.randomUUID());
        traveler.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        traveler.setMobileMoneyCurrency("XOF");
        traveler.setMobileMoneyMsisdn("221770000002");
        traveler.setMobileMoneyProvider("ORANGE_SEN");
        traveler.setMobileMoneyProviderList(List.of("ORANGE_SEN", "WAVE_SEN"));
        traveler.setMobileMoneyCountry("SN");
        givenAccounts(account("ORANGE_SEN", "SN", "221770000002", true),
                account("WAVE_SEN", "SN", "221770000003", false));
        s = new BidSupplementEntity();
        setId(s, UUID.randomUUID());
        s.setBidId(UUID.randomUUID());
        s.setSenderId(UUID.randomUUID());
        s.setTravelerId(traveler.getId());
        s.setRail(BidSupplementRail.PAWAPAY);
        s.setStatus(BidSupplementStatus.PAID);
        s.setCurrency("XOF");
        s.setNetAmount(new BigDecimal("13000"));
        s.setGrossAmount(new BigDecimal("13700"));
        claim = new BidSupplementMobileMoneyLedger.Claim(s.getId(), s.getBidId(), s.getSenderId(), traveler.getId(),
                s.getNetAmount(), s.getGrossAmount(), "XOF", BidSupplementStatus.PAYOUT_PENDING, LocalDateTime.now());
        principal = new PaymentEntity();
        setId(principal, UUID.randomUUID());
        principal.setRail(PaymentRail.PAWAPAY);
        lenient().when(supplements.findById(s.getId())).thenReturn(Optional.of(s));
        lenient().when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        lenient().when(ledger.beginPayout(s.getId())).thenReturn(Optional.of(claim));
        lenient().when(payments.findForBid(s.getBidId())).thenReturn(Optional.of(principal));
        lenient().when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
    }

    private MobileMoneyPayoutAccountEntity account(String provider, String country, String msisdn, boolean migrated) {
        MobileMoneyPayoutAccountEntity a = new MobileMoneyPayoutAccountEntity(traveler.getId(), provider, country, "XOF",
                msisdn, "masked", Instant.parse("2026-03-04T10:11:12Z"));
        a.setMigrated(migrated);
        setId(a, UUID.randomUUID());
        return a;
    }

    private void givenAccounts(MobileMoneyPayoutAccountEntity... live) {
        lenient().when(accountRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(traveler.getId()))
                .thenReturn(List.of(live));
    }

    private static com.yadony.api.payments.pawapay.dto.PawapayProviderConfig config(String provider, String min,
                                                                                   String max) {
        com.yadony.api.payments.pawapay.dto.PawapayProviderConfig.Limits limits =
                new com.yadony.api.payments.pawapay.dto.PawapayProviderConfig.Limits(
                        min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max), null, null);
        return new com.yadony.api.payments.pawapay.dto.PawapayProviderConfig(provider, "SEN", "XOF", limits, limits);
    }

    private PawapayOperationEntity op(PawapayOperationKind kind, PawapayOperationStatus status, String msisdn,
                                      String provider) {
        PawapayOperationEntity op = PawapayOperationEntity.forSupplement(UUID.randomUUID(), kind, traveler.getId(),
                s.getId(), null, s.getNetAmount(), "XOF", provider, "SN", msisdn);
        op.setStatus(status);
        return op;
    }

    /**
     * Le principal est passé par WAVE (numéro ...099) mais le supplément a été payé en ORANGE : le verrou
     * protège le compte ORANGE, c'est lui qui reçoit, jamais le numéro figé du principal.
     */
    @Test
    void versement_compteDuCodeDuDepotDuSupplement_pasLeNumeroFigeDuPrincipal() {
        PawapayOperationEntity principalPayout = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                principal.getId(), null, new BigDecimal("50000"), "XOF", "WAVE_SEN", "SN", "221770000099");
        principalPayout.setStatus(PawapayOperationStatus.COMPLETED);
        lenient().when(operations.findLive(principal.getId(), PawapayOperationKind.PAYOUT))
                .thenReturn(Optional.of(principalPayout));
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT))
                .thenReturn(Optional.of(op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED,
                        "221770000001", "ORANGE_SEN")));
        PawapayOperationEntity submitted = op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED,
                "221770000002", "ORANGE_SEN");
        when(submission.submitSupplementPayout(s.getId(), traveler.getId(), "221770000002", "ORANGE_SEN", "SN",
                new BigDecimal("13000"), "XOF", "supp-" + s.getId())).thenReturn(submitted);

        assertThat(payouts.payout(s.getId())).isTrue();

        verify(ledger).auditOnly(eq(s.getId()), eq("BID_SUPPLEMENT_PAYOUT_SUBMITTED"), anyMap());
        verify(ledger, never()).revertPayout(any(), any());
    }

    private void givenCompletedPrincipalPayout(String provider, String msisdn) {
        PawapayOperationEntity principalPayout = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                principal.getId(), null, new BigDecimal("50000"), "XOF", provider, "SN", msisdn);
        principalPayout.setStatus(PawapayOperationStatus.COMPLETED);
        when(operations.findLive(principal.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.of(principalPayout));
    }

    /** Sans dépôt lisible : le compte vivant qui porte encore le code ET le numéro du principal est repris. */
    @Test
    void versement_sansDepotLisible_compteQuiPorteEncoreLeCodeEtLeNumeroDuPrincipal() {
        MobileMoneyPayoutAccountEntity wave = account("WAVE_SEN", "SN", "221770000099", false);
        givenAccounts(account("ORANGE_SEN", "SN", "221770000002", true), wave);
        givenCompletedPrincipalPayout("WAVE_SEN", "221770000099");
        when(submission.submitSupplementPayout(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED, "221770000099", "WAVE_SEN"));

        assertThat(payouts.payout(s.getId())).isTrue();

        verify(submission).submitSupplementPayout(s.getId(), traveler.getId(), "221770000099", "WAVE_SEN", "SN",
                new BigDecimal("13000"), "XOF", "supp-" + s.getId());
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(ledger).auditOnly(eq(s.getId()), eq("BID_SUPPLEMENT_PAYOUT_SUBMITTED"), audit.capture());
        assertThat(audit.getValue()).containsEntry("accountId", wave.getId().toString());
    }

    /** Le compte du principal a changé de numéro depuis : son ancien numéro n'est jamais réutilisé. */
    @Test
    void versement_sansDepotLisible_numeroDuPrincipalChange_pasDeReutilisationDeLAncienNumero() {
        givenAccounts(account("WAVE_SEN", "SN", "221770000555", false));
        givenCompletedPrincipalPayout("WAVE_SEN", "221770000099");

        assertThat(payouts.payout(s.getId())).isFalse();

        verify(submission, never()).submitSupplementPayout(any(), any(), eq("221770000099"), any(), any(), any(), any(),
                any());
        verify(alerts).raiseOnce(eq("SUPP_PAYOUT_BLOCKED_" + s.getId()), anyString(), anyMap());
        verify(ledger, never()).beginPayout(any());
    }

    @Test
    void destination_toString_neMontrePasLeNumero() {
        String text = new BidSupplementMobileMoneyPayouts.Destination("221770000002", "ORANGE_SEN", "SN", UUID.randomUUID())
                .toString();
        assertThat(text).doesNotContain("221770000002").contains("ORANGE_SEN");
    }

    @Test
    void versement_sansPayoutPrincipalLisible_compteDuMemeCodeQueLeDepot() {
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT))
                .thenReturn(Optional.of(op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED,
                        "221770000001", "WAVE_SEN")));
        when(submission.submitSupplementPayout(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED, "221770000003", "WAVE_SEN"));

        payouts.payout(s.getId());

        verify(submission).submitSupplementPayout(s.getId(), traveler.getId(), "221770000003", "WAVE_SEN", "SN",
                new BigDecimal("13000"), "XOF", "supp-" + s.getId());
    }

    /** Review Focus 2 : même marque, deux pays. Le dépôt SEN est versé sur le compte sénégalais. */
    @Test
    void versement_deuxPaysDeLaMemeMarque_compteDuPaysDuDepot_etAuditAvecAccountId() {
        MobileMoneyPayoutAccountEntity civ = account("ORANGE_CIV", "CI", "2250700000001", false);
        MobileMoneyPayoutAccountEntity sen = account("ORANGE_SEN", "SN", "221770000002", false);
        givenAccounts(civ, sen);
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT))
                .thenReturn(Optional.of(op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED,
                        "221770000001", "ORANGE_SEN")));
        when(submission.submitSupplementPayout(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED, "221770000002", "ORANGE_SEN"));

        assertThat(payouts.payout(s.getId())).isTrue();

        verify(submission).submitSupplementPayout(s.getId(), traveler.getId(), "221770000002", "ORANGE_SEN", "SN",
                new BigDecimal("13000"), "XOF", "supp-" + s.getId());
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(ledger).auditOnly(eq(s.getId()), eq("BID_SUPPLEMENT_PAYOUT_SUBMITTED"), audit.capture());
        assertThat(audit.getValue()).containsEntry("provider", "ORANGE_SEN")
                .containsEntry("accountId", sen.getId().toString());
        assertThat(audit.getValue().values()).noneMatch(v -> String.valueOf(v).contains("221770000002"));
    }

    /**
     * Review Focus 1 : voyageur hérité, dépôt du supplément sur MTN. Le seul compte est le compte repris
     * (Orange) : le versement part sur son numéro et son code, jamais sur MTN vers un numéro Orange.
     */
    @Test
    void versement_voyageurHerite_depotAutreReseau_compteRepris_sonNumeroEtSonCode() {
        MobileMoneyPayoutAccountEntity orange = account("ORANGE_SEN", "SN", "221770000002", true);
        givenAccounts(orange);
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT))
                .thenReturn(Optional.of(op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED,
                        "221770000001", "MTN_MOMO_SEN")));
        when(submission.submitSupplementPayout(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED, "221770000002", "ORANGE_SEN"));

        assertThat(payouts.payout(s.getId())).isTrue();

        verify(submission).submitSupplementPayout(s.getId(), traveler.getId(), "221770000002", "ORANGE_SEN", "SN",
                new BigDecimal("13000"), "XOF", "supp-" + s.getId());
        verify(submission, never()).submitSupplementPayout(any(), any(), any(), eq("MTN_MOMO_SEN"), any(), any(), any(),
                any());
    }

    /** Aucun compte du code du dépôt ni compte repris : retenu, alerte, aucune réservation ni appel pawaPay. */
    @Test
    void versement_aucuneDestination_restePaye_alerte_sansAppelPawapay() {
        givenAccounts(account("ORANGE_CIV", "CI", "2250700000001", false));
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT))
                .thenReturn(Optional.of(op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED,
                        "221770000001", "ORANGE_SEN")));

        assertThat(payouts.payout(s.getId())).isFalse();

        verify(alerts).raiseOnce(eq("SUPP_PAYOUT_BLOCKED_" + s.getId()), anyString(), anyMap());
        verify(ledger, never()).beginPayout(any());
        verifyNoInteractions(submission);
    }

    @Test
    void reprise_aucuneDestination_retourAPaye_sansAppelPawapay() {
        s.setStatus(BidSupplementStatus.PAYOUT_PENDING);
        givenAccounts(account("ORANGE_CIV", "CI", "2250700000001", false));
        when(ledger.pendingPayout(s.getId())).thenReturn(Optional.of(claim));
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT))
                .thenReturn(Optional.of(op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED,
                        "221770000001", "ORANGE_SEN")));

        assertThat(payouts.resume(s.getId())).isFalse();

        verify(ledger).revertPayout(s.getId(), "PAYOUT_GUARD");
        verifyNoInteractions(submission);
    }

    @Test
    void versement_retenueLitigeOuCompteAbsent_restePaye_alerte() {
        when(holdPolicy.isHeld(traveler.getId())).thenReturn(true);
        assertThat(payouts.payout(s.getId())).isFalse();

        when(holdPolicy.isHeld(traveler.getId())).thenReturn(false);
        when(disputes.existsByBidIdAndStatusAndTypeStartingWith(s.getBidId(), DisputeTypes.STATUS_OPEN,
                DisputeTypes.ADMIN_PREFIX)).thenReturn(true);
        assertThat(payouts.payout(s.getId())).isFalse();

        when(disputes.existsByBidIdAndStatusAndTypeStartingWith(any(), any(), any())).thenReturn(false);
        traveler.setMobileMoneyCurrency("XAF");
        assertThat(payouts.payout(s.getId())).isFalse();

        verify(alerts, org.mockito.Mockito.times(3)).raiseOnce(eq("SUPP_PAYOUT_BLOCKED_" + s.getId()), anyString(),
                anyMap());
        verify(ledger, never()).beginPayout(any());
        verifyNoInteractions(submission);
    }

    @Test
    void versement_supplementPasPayeOuAutreRail_rien() {
        s.setStatus(BidSupplementStatus.PAID_OUT);
        assertThat(payouts.payout(s.getId())).isFalse();
        s.setStatus(BidSupplementStatus.PAID);
        s.setRail(BidSupplementRail.STRIPE);
        assertThat(payouts.payout(s.getId())).isFalse();
        assertThat(payouts.payout(UUID.randomUUID())).isFalse();
        verifyNoInteractions(submission);
    }

    @Test
    void versement_reservationPerdue_rien() {
        when(ledger.beginPayout(s.getId())).thenReturn(Optional.empty());
        assertThat(payouts.payout(s.getId())).isFalse();
        verifyNoInteractions(submission);
    }

    @Test
    void versement_refuseParPawapay_retourAPayeEtAlerte() {
        PawapayOperationEntity rejected = op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.SUBMIT_REJECTED,
                "221770000002", "ORANGE_SEN");
        rejected.setFailureCode("RECIPIENT_NOT_FOUND");
        when(submission.submitSupplementPayout(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(rejected);

        assertThat(payouts.payout(s.getId())).isFalse();

        verify(ledger).revertPayout(s.getId(), "RECIPIENT_NOT_FOUND");
        verify(alerts).raiseOnce(eq("SUPP_MM_PAYOUT_FAILED_" + s.getId()), anyString(), anyMap());
    }

    @Test
    void versement_erreurAvantToutePoperation_retourAPaye_sinonLaisseAuPoller() {
        when(submission.submitSupplementPayout(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        assertThat(payouts.payout(s.getId())).isFalse();
        verify(ledger).revertPayout(s.getId(), "boom");

        PawapayOperationEntity created = op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.CREATED,
                "221770000002", "ORANGE_SEN");
        lenient().when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.PAYOUT))
                .thenReturn(Optional.empty(), Optional.of(created));
        org.mockito.Mockito.doThrow(new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                        "mobile-money-provider-unavailable", "Down", "panne"))
                .when(submission).submitSupplementPayout(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(payouts.payout(s.getId())).as("opération créée, réseau muet").isTrue();
        verify(ledger).revertPayout(any(), any());
    }

    @Test
    void reprise_payoutAbouti_enregistre_payoutEnCours_attend() {
        when(ledger.pendingPayout(s.getId())).thenReturn(Optional.of(claim));
        PawapayOperationEntity completed = op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.COMPLETED,
                "221770000002", "ORANGE_SEN");
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.of(completed));
        assertThat(payouts.resume(s.getId())).isTrue();
        verify(ledger).completePayout(s.getId(), completed.getId());

        PawapayOperationEntity open = op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED,
                "221770000002", "ORANGE_SEN");
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.of(open));
        assertThat(payouts.resume(s.getId())).isTrue();
        verify(ledger, never()).completePayout(s.getId(), open.getId());
        verifyNoInteractions(submission);

        when(ledger.pendingPayout(s.getId())).thenReturn(Optional.empty());
        assertThat(payouts.resume(s.getId())).isFalse();
    }

    @Test
    void versementDuColis_chaqueSupplementPaye() {
        when(supplements.findByBidIdAndRailAndStatus(s.getBidId(), BidSupplementRail.PAWAPAY, BidSupplementStatus.PAID))
                .thenReturn(List.of(s));
        when(submission.submitSupplementPayout(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED, "221770000002", "ORANGE_SEN"));
        payouts.payoutForBid(s.getBidId());
        verify(submission).submitSupplementPayout(eq(s.getId()), any(), any(), any(), any(), any(), any(),
                startsWith("supp-"));
    }

    // ── Corrections de relecture ─────────────────────────────────────────────

    @Test
    void versement_apresTroisEchecs_plusDeSoumission_alerteManuelle() {
        when(operations.countDeadForSupplement(s.getId(), PawapayOperationKind.PAYOUT)).thenReturn(3L);

        assertThat(payouts.payout(s.getId())).isFalse();

        verify(alerts).raiseOnce(eq("SUPP_MM_PAYOUT_MANUAL_" + s.getId()), anyString(), anyMap());
        verify(ledger, never()).beginPayout(any());
        verifyNoInteractions(submission);
    }

    @Test
    void versement_plafondReglable() {
        properties.setMmMaxAttempts(5);
        when(operations.countDeadForSupplement(s.getId(), PawapayOperationKind.PAYOUT)).thenReturn(3L);
        when(submission.submitSupplementPayout(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED, "221770000002", "ORANGE_SEN"));
        assertThat(payouts.payout(s.getId())).isTrue();
    }

    @Test
    void versement_horsLimitesDeLOperateur_pasDeSoumission_restePaye() {
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", config("ORANGE_SEN", "100", "5000")));

        assertThat(payouts.payout(s.getId())).isFalse();

        verify(alerts).raiseOnce(eq("SUPP_MM_PAYOUT_LIMITS_" + s.getId()), anyString(), anyMap());
        verify(ledger, never()).beginPayout(any());
        verifyNoInteractions(submission);
    }

    @Test
    void versement_operateurAbsentDeLaConfiguration_pasDeSoumission() {
        when(client.activeConfiguration()).thenReturn(Map.of());
        assertThat(payouts.payout(s.getId())).isFalse();
        verify(alerts).raiseOnce(eq("SUPP_MM_PAYOUT_LIMITS_" + s.getId()), anyString(), anyMap());
        verifyNoInteractions(submission);
    }

    @Test
    void versement_configurationIllisible_soumisQuandMeme() {
        when(client.activeConfiguration()).thenThrow(new IllegalStateException("pawaPay muet"));
        when(submission.submitSupplementPayout(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED, "221770000002", "ORANGE_SEN"));
        assertThat(payouts.payout(s.getId())).isTrue();
    }

    @Test
    void reprise_sansOperation_rejoueLesGardes_avantDeSoumettre() {
        s.setStatus(BidSupplementStatus.PAYOUT_PENDING);
        when(ledger.pendingPayout(s.getId())).thenReturn(Optional.of(claim));
        when(holdPolicy.isHeld(traveler.getId())).thenReturn(true);

        assertThat(payouts.resume(s.getId())).isFalse();

        verify(ledger).revertPayout(s.getId(), "PAYOUT_HOLD");
        verify(alerts).raiseOnce(eq("SUPP_PAYOUT_BLOCKED_" + s.getId()), anyString(), anyMap());
        verifyNoInteractions(submission);
    }

    @Test
    void reprise_sansOperation_plafondAtteint_retourAPaye() {
        s.setStatus(BidSupplementStatus.PAYOUT_PENDING);
        when(ledger.pendingPayout(s.getId())).thenReturn(Optional.of(claim));
        when(operations.countDeadForSupplement(s.getId(), PawapayOperationKind.PAYOUT)).thenReturn(3L);

        assertThat(payouts.resume(s.getId())).isFalse();

        verify(ledger).revertPayout(eq(s.getId()), anyString());
        verifyNoInteractions(submission);
    }

    @Test
    void reprise_sansOperation_gardesLevees_soumet() {
        s.setStatus(BidSupplementStatus.PAYOUT_PENDING);
        when(ledger.pendingPayout(s.getId())).thenReturn(Optional.of(claim));
        when(submission.submitSupplementPayout(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED, "221770000002", "ORANGE_SEN"));
        assertThat(payouts.resume(s.getId())).isTrue();
        verify(ledger, never()).revertPayout(any(), any());
    }
}
