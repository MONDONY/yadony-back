package com.yadony.api.payments.amendment;

import com.stripe.exception.ApiConnectionException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.StripeSupplementGateway.ChargeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Versement du net d'une modification carte : gardes, reprise, échecs de Stripe et d'enregistrement. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BidAmendmentPayouts")
class BidAmendmentPayoutsTest {

    @Mock StripeAmendmentGateway gateway;
    @Mock BidAmendmentCardLedger ledger;
    @Mock BidAmendmentRepository amendments;
    @Mock UserRepository users;
    @Mock PayoutHoldPolicy holdPolicy;
    @Mock DisputeRepository disputes;
    @Mock AdminAlertEscalator alerts;

    private BidAmendmentPayouts payouts;
    private BidAmendmentEntity a;
    private UserEntity traveler;
    private BidAmendmentCardLedger.PayoutClaim claim;

    @BeforeEach
    void setUp() throws Exception {
        payouts = new BidAmendmentPayouts(gateway, ledger, amendments, users, holdPolicy, disputes, alerts);
        traveler = new UserEntity();
        BidAmendmentTestSupport.setId(traveler, UUID.randomUUID());
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        traveler.setStripeAccountId("acct_traveler");
        a = new BidAmendmentEntity();
        BidAmendmentTestSupport.setId(a, UUID.randomUUID());
        a.setBidId(UUID.randomUUID());
        a.setTravelerId(traveler.getId());
        a.setRail(BidSupplementRail.STRIPE);
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        a.setCurrency("EUR");
        a.setNetAmount(new BigDecimal("30.00"));
        a.setStripePaymentIntentId("pi_1");
        a.setStripeChargeId("ch_1");
        claim = new BidAmendmentCardLedger.PayoutClaim(a.getId(), a.getBidId(), traveler.getId(),
                new BigDecimal("30.00"), "EUR", "ch_1", "pi_1", false);
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));
        when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(gateway.retrieveCharge("ch_1")).thenReturn(new ChargeView(false, 0L));
        when(gateway.findTransferByGroup("amendment_" + a.getId())).thenReturn(Optional.empty());
        when(ledger.beginPayout(a.getId())).thenReturn(Optional.of(claim));
        when(gateway.createTransfer(eq(3000L), eq("eur"), eq("acct_traveler"), eq("ch_1"),
                eq("amendment_" + a.getId()), anyMap(), eq("amendment-transfer-" + a.getId()))).thenReturn("tr_1");
        when(ledger.completePayout(a.getId(), "tr_1", "ch_1")).thenReturn(true);
    }

    // ── Gardes : la modification reste acceptée, alerte, le filet réessaiera ──

    @Test
    @DisplayName("litige ouvert par l'administration sur le colis : versement retenu")
    void adminDisputeOpen_blocks() {
        when(disputes.existsByBidIdAndStatusAndTypeStartingWith(a.getBidId(), DisputeTypes.STATUS_OPEN,
                DisputeTypes.ADMIN_PREFIX)).thenReturn(true);

        assertThat(payouts.payout(a.getId())).isFalse();

        assertBlocked("DISPUTE_OPEN");
    }

    @Test
    @DisplayName("compte Connect du voyageur incomplet : versement retenu")
    void accountNotReady_blocks() {
        traveler.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);

        assertThat(payouts.payout(a.getId())).isFalse();

        assertBlocked("STRIPE_ACCOUNT_NOT_READY");
    }

    @Test
    @DisplayName("voyageur aux versements gelés : motif PAYOUT_HOLD")
    void held_blocksWithReason() {
        when(holdPolicy.isHeld(traveler.getId())).thenReturn(true);

        assertThat(payouts.payout(a.getId())).isFalse();

        assertBlocked("PAYOUT_HOLD");
    }

    @Test
    @DisplayName("charge de la modification contestée par la banque : versement retenu")
    void chargeDisputed_blocks() throws Exception {
        when(gateway.retrieveCharge("ch_1")).thenReturn(new ChargeView(true, 0L));

        assertThat(payouts.payout(a.getId())).isFalse();

        assertBlocked("CHARGE_DISPUTED");
    }

    @Test
    @DisplayName("charge déjà remboursée en partie : versement retenu")
    void chargePartiallyRefunded_blocks() throws Exception {
        when(gateway.retrieveCharge("ch_1")).thenReturn(new ChargeView(false, 100L));

        assertThat(payouts.payout(a.getId())).isFalse();

        assertBlocked("CHARGE_PARTIALLY_REFUNDED");
    }

    @Test
    @DisplayName("état de la charge illisible : versement retenu, jamais versé à l'aveugle")
    void chargeUnreadable_blocks() throws Exception {
        when(gateway.retrieveCharge("ch_1")).thenThrow(new ApiConnectionException("réseau"));

        assertThat(payouts.payout(a.getId())).isFalse();

        assertBlocked("CHARGE_CHECK_FAILED");
    }

    @Test
    @DisplayName("modification d'un autre rail, ou déjà versée : rien, ni alerte ni réservation")
    void notAcceptedCard_ignored() {
        a.setStatus(BidAmendmentStatus.PAID_OUT);
        assertThat(payouts.payout(a.getId())).isFalse();
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        a.setRail(BidSupplementRail.PAWAPAY);
        assertThat(payouts.payout(a.getId())).isFalse();

        verify(ledger, never()).beginPayout(any());
        verifyNoInteractions(alerts);
    }

    // ── Échecs ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Transfer refusé par Stripe (4xx, rien n'est créé) : retour à ACCEPTED, alerte, aucune exception")
    void transferRefused4xx_revertsAndAlerts() throws Exception {
        when(gateway.createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap(),
                anyString())).thenThrow(new com.stripe.exception.InvalidRequestException("solde insuffisant", null,
                "req_1", "balance_insufficient", 400, null));

        assertThatCode(() -> assertThat(payouts.payout(a.getId())).isFalse()).doesNotThrowAnyException();

        verify(ledger).revertPayout(eq(a.getId()), anyString());
        verify(alerts).raiseOnce(eq("AMEND_TRANSFER_FAILED_" + a.getId()), anyString(), anyMap());
        verify(ledger, never()).completePayout(any(), any(), any());
    }

    @Test
    @DisplayName("Transfer parti mais enregistrement en échec : jamais de retour à ACCEPTED (il repartirait), alerte")
    void recordFails_keepsPendingAndAlerts() {
        when(ledger.completePayout(a.getId(), "tr_1", "ch_1")).thenThrow(new IllegalStateException("base"));

        assertThatCode(() -> assertThat(payouts.payout(a.getId())).isFalse()).doesNotThrowAnyException();

        verify(ledger, never()).revertPayout(any(), any());
        verify(alerts).raiseOnce(eq("AMEND_PAYOUT_UNRECORD_" + a.getId()), anyString(), anyMap());
    }

    @Test
    @DisplayName("Transfer déjà créé (clé expirée, reprise) : retrouvé par son groupe, jamais recréé")
    void existingTransfer_reused() throws Exception {
        when(gateway.findTransferByGroup("amendment_" + a.getId())).thenReturn(Optional.of("tr_old"));
        when(ledger.completePayout(a.getId(), "tr_old", "ch_1")).thenReturn(true);

        assertThat(payouts.payout(a.getId())).isTrue();

        verify(gateway, never()).createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), anyString());
        verify(ledger).completePayout(a.getId(), "tr_old", "ch_1");
    }

    @Test
    @DisplayName("reprise d'un versement resté PAYOUT_PENDING : Transfer (ou retrouvé), puis PAID_OUT")
    void resume_pendingPayout_transfers() throws Exception {
        when(ledger.pendingPayout(a.getId())).thenReturn(Optional.of(claim));

        assertThat(payouts.resume(a.getId())).isTrue();

        verify(gateway).createTransfer(eq(3000L), eq("eur"), eq("acct_traveler"), eq("ch_1"),
                eq("amendment_" + a.getId()), anyMap(), eq("amendment-transfer-" + a.getId()));
        verify(ledger).completePayout(a.getId(), "tr_1", "ch_1");
        verify(ledger, never()).beginPayout(any());
    }

    @Test
    @DisplayName("charge de la modification inconnue : versement retenu (CHARGE_UNKNOWN), Stripe jamais lu")
    void chargeUnknown_blocks() throws Exception {
        a.setStripeChargeId(null);

        assertThat(payouts.payout(a.getId())).isFalse();

        assertBlocked("CHARGE_UNKNOWN");
        verify(gateway, never()).retrieveCharge(any());
    }

    @Test
    @DisplayName("reprise alors que le compte Connect n'est plus prêt : aucun Transfer, retour à ACCEPTED, alerte BLOCKED")
    void resume_accountGone_revertsAndAlerts() throws Exception {
        when(ledger.pendingPayout(a.getId())).thenReturn(Optional.of(claim));
        traveler.setStripeAccountStatus(StripeAccountStatus.DISABLED);

        assertThat(payouts.resume(a.getId())).isFalse();

        verify(gateway, never()).createTransfer(anyLong(), anyString(), any(), any(), anyString(), anyMap(),
                anyString());
        verify(ledger).revertPayout(eq(a.getId()), anyString());
        verify(alerts).raiseOnce(eq("AMEND_PAYOUT_BLOCKED_" + a.getId()), anyString(), anyMap());
    }

    @Test
    @DisplayName("reprise sans charge connue : aucun Transfer à l'aveugle, retour à ACCEPTED, alerte")
    void resume_noCharge_revertsAndAlerts() throws Exception {
        when(ledger.pendingPayout(a.getId())).thenReturn(Optional.of(new BidAmendmentCardLedger.PayoutClaim(a.getId(),
                a.getBidId(), traveler.getId(), new BigDecimal("30.00"), "EUR", null, "pi_1", false)));

        assertThat(payouts.resume(a.getId())).isFalse();

        verify(gateway, never()).createTransfer(anyLong(), anyString(), any(), any(), anyString(), anyMap(),
                anyString());
        verify(ledger).revertPayout(eq(a.getId()), anyString());
    }

    @Test
    @DisplayName("colis à plusieurs modifications : un versement en échec n'empêche pas les suivants")
    void payoutForBid_oneFailure_doesNotStopOthers() {
        BidAmendmentEntity broken = new BidAmendmentEntity();
        BidAmendmentTestSupport.setId(broken, UUID.randomUUID());
        when(amendments.findByBidIdAndRailAndStatus(a.getBidId(), BidSupplementRail.STRIPE,
                BidAmendmentStatus.ACCEPTED)).thenReturn(java.util.List.of(broken, a));
        when(amendments.findById(broken.getId())).thenThrow(new IllegalStateException("base"));

        assertThatCode(() -> payouts.payoutForBid(a.getBidId())).doesNotThrowAnyException();

        verify(ledger).completePayout(a.getId(), "tr_1", "ch_1");
    }

    @Test
    @DisplayName("Transfer à l'issue inconnue (coupure réseau) : reste PAYOUT_PENDING, jamais ACCEPTED, alerte")
    void transferUncertain_staysPendingAndAlerts() throws Exception {
        when(gateway.createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap(),
                anyString())).thenThrow(new ApiConnectionException("délai dépassé"));

        assertThatCode(() -> assertThat(payouts.payout(a.getId())).isFalse()).doesNotThrowAnyException();

        verify(ledger, never()).revertPayout(any(), any());
        verify(alerts).raiseOnce(eq("AMEND_TRANSFER_UNKNOWN_" + a.getId()), anyString(), anyMap());
    }

    @Test
    @DisplayName("Transfer en erreur 5xx : issue inconnue, reste PAYOUT_PENDING")
    void transferServerError_staysPending() throws Exception {
        when(gateway.createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap(),
                anyString())).thenThrow(new com.stripe.exception.ApiException("boom", "req_2", null, 500, null));

        assertThat(payouts.payout(a.getId())).isFalse();

        verify(ledger, never()).revertPayout(any(), any());
        verify(alerts).raiseOnce(eq("AMEND_TRANSFER_UNKNOWN_" + a.getId()), anyString(), anyMap());
    }

    @Test
    @DisplayName("recherche du Transfer impossible : aucun envoi, reste PAYOUT_PENDING (un essai précédent a pu partir)")
    void lookupFails_staysPending() throws Exception {
        when(gateway.findTransferByGroup("amendment_" + a.getId())).thenThrow(new ApiConnectionException("réseau"));

        assertThat(payouts.payout(a.getId())).isFalse();

        verify(gateway, never()).createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), anyString());
        verify(ledger, never()).revertPayout(any(), any());
        verify(alerts).raiseOnce(eq("AMEND_TRANSFER_UNKNOWN_" + a.getId()), anyString(), anyMap());
    }

    @Test
    @DisplayName("reprise : retenue posée depuis la réservation et aucun Transfer → aucun envoi, ACCEPTED, alerte BLOCKED")
    void resume_heldAfterClaim_doesNotTransfer() throws Exception {
        when(ledger.pendingPayout(a.getId())).thenReturn(Optional.of(claim));
        when(holdPolicy.isHeld(traveler.getId())).thenReturn(true);

        assertThat(payouts.resume(a.getId())).isFalse();

        verify(gateway, never()).createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), anyString());
        verify(ledger).revertPayout(eq(a.getId()), anyString());
        verify(alerts).raiseOnce(eq("AMEND_PAYOUT_BLOCKED_" + a.getId()), anyString(), anyMap());
    }

    @Test
    @DisplayName("reprise : remboursement demandé pendant le versement et aucun Transfer → aucun envoi, rendu au registre")
    void resume_refundRequested_noTransfer_reverts() throws Exception {
        when(ledger.pendingPayout(a.getId())).thenReturn(Optional.of(new BidAmendmentCardLedger.PayoutClaim(a.getId(),
                a.getBidId(), traveler.getId(), new BigDecimal("30.00"), "EUR", "ch_1", "pi_1", true)));

        assertThat(payouts.resume(a.getId())).isFalse();

        verify(gateway, never()).createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), anyString());
        verify(ledger).revertPayout(eq(a.getId()), anyString());
    }

    @Test
    @DisplayName("modification dont le remboursement est demandé : jamais versée, sans alerte")
    void payout_refundRequested_ignored() {
        a.setRefundRequestedAt(java.time.LocalDateTime.now());

        assertThat(payouts.payout(a.getId())).isFalse();

        verify(ledger, never()).beginPayout(any());
        verifyNoInteractions(alerts);
    }

    private void assertBlocked(String reason) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(alerts).raiseOnce(eq("AMEND_PAYOUT_BLOCKED_" + a.getId()), anyString(), context.capture());
        assertThat(context.getValue()).containsEntry("reason", reason)
                .containsEntry("amendmentId", a.getId().toString());
        verify(ledger, never()).beginPayout(any());
        try {
            verify(gateway, never()).createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(),
                    anyMap(), anyString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
