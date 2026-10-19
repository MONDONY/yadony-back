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
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
    @Mock PaymentRepository payments;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock PayoutHoldPolicy holdPolicy;
    @Mock DisputeRepository disputes;
    @Mock AdminAlertEscalator alerts;

    private BidSupplementMobileMoneyPayouts payouts;
    private BidSupplementEntity s;
    private UserEntity traveler;
    private PaymentEntity principal;
    private BidSupplementMobileMoneyLedger.Claim claim;

    @BeforeEach
    void setUp() {
        payouts = new BidSupplementMobileMoneyPayouts(ledger, supplements, users, payments, operations, submission,
                holdPolicy, disputes, alerts);
        traveler = new UserEntity();
        setId(traveler, UUID.randomUUID());
        traveler.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        traveler.setMobileMoneyCurrency("XOF");
        traveler.setMobileMoneyMsisdn("221770000002");
        traveler.setMobileMoneyProvider("ORANGE_SEN");
        traveler.setMobileMoneyProviderList(List.of("ORANGE_SEN", "WAVE_SEN"));
        traveler.setMobileMoneyCountry("SN");
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

    private PawapayOperationEntity op(PawapayOperationKind kind, PawapayOperationStatus status, String msisdn,
                                      String provider) {
        PawapayOperationEntity op = PawapayOperationEntity.forSupplement(UUID.randomUUID(), kind, traveler.getId(),
                s.getId(), null, s.getNetAmount(), "XOF", provider, "SN", msisdn);
        op.setStatus(status);
        return op;
    }

    @Test
    void versement_memeDestinationQueLePrincipal_netDuSupplement() {
        PawapayOperationEntity principalPayout = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                principal.getId(), null, new BigDecimal("50000"), "XOF", "WAVE_SEN", "SN", "221770000099");
        principalPayout.setStatus(PawapayOperationStatus.COMPLETED);
        when(operations.findLive(principal.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.of(principalPayout));
        PawapayOperationEntity submitted = op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED,
                "221770000099", "WAVE_SEN");
        when(submission.submitSupplementPayout(s.getId(), traveler.getId(), "221770000099", "WAVE_SEN", "SN",
                new BigDecimal("13000"), "XOF", "supp-" + s.getId())).thenReturn(submitted);

        assertThat(payouts.payout(s.getId())).isTrue();

        verify(ledger).auditOnly(eq(s.getId()), eq("BID_SUPPLEMENT_PAYOUT_SUBMITTED"), anyMap());
        verify(ledger, never()).revertPayout(any(), any());
    }

    @Test
    void versement_sansPayoutPrincipalLisible_compteDuVoyageurSurLeReseauDuDepot() {
        when(operations.findLive(principal.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.DEPOSIT))
                .thenReturn(Optional.of(op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED,
                        "221770000001", "WAVE_SEN")));
        when(submission.submitSupplementPayout(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED, "221770000002", "WAVE_SEN"));

        payouts.payout(s.getId());

        verify(submission).submitSupplementPayout(s.getId(), traveler.getId(), "221770000002", "WAVE_SEN", "SN",
                new BigDecimal("13000"), "XOF", "supp-" + s.getId());
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
        when(operations.findLiveForSupplement(s.getId(), PawapayOperationKind.PAYOUT))
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
}
