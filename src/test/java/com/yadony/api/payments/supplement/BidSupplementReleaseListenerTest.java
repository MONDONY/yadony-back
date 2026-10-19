package com.yadony.api.payments.supplement;

import com.stripe.exception.ApiConnectionException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import com.yadony.api.payments.supplement.events.BidSupplementPaidOutEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.payments.supplement.BidSupplementPricingTest.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Versement du supplément carte sur {@code PaymentReleasedEvent} : Transfer adossé à la charge du
 * supplément, groupe {@code supplement_<id>}, clé dédiée, un seul Transfer même si l'événement
 * arrive deux fois, retenue et échec sans perte (le supplément reste payé).
 */
@ExtendWith(MockitoExtension.class)
class BidSupplementReleaseListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    @Mock StripeSupplementGateway gateway;
    @Mock BidSupplementRepository supplements;
    @Mock BidRepository bids;
    @Mock UserRepository users;
    @Mock PayoutHoldPolicy holdPolicy;
    @Mock DisputeRepository disputes;
    @Mock AdminAlertEscalator alerts;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher events;

    private BidSupplementCardPayouts payouts;
    private BidSupplementCardListener listener;
    private BidSupplementEntity s;
    private UserEntity traveler;

    @BeforeEach
    void setUp() throws Exception {
        // Registre réel : la réservation exclusive PAID → PAYOUT_PENDING est la garde anti double versement.
        BidSupplementCardLedger ledger = new BidSupplementCardLedger(supplements, bids, auditService, events,
                new BidSupplementWindow(new BidSupplementProperties(), Clock.fixed(NOW, ZoneOffset.UTC)));
        payouts = new BidSupplementCardPayouts(gateway, ledger, supplements, users, holdPolicy, disputes, alerts);
        listener = new BidSupplementCardListener(mock(BidSupplementCardRail.class), payouts,
                mock(BidSupplementCardRefunds.class));

        traveler = new UserEntity();
        setId(traveler, UUID.randomUUID());
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(com.yadony.api.auth.StripeAccountStatus.ONBOARDING_COMPLETE);
        BidEntity bid = new BidEntity();
        setId(bid, UUID.randomUUID());
        bid.setStatus(BidStatus.COMPLETED);
        s = new BidSupplementEntity();
        setId(s, UUID.randomUUID());
        s.setBidId(bid.getId());
        s.setSenderId(UUID.randomUUID());
        s.setTravelerId(traveler.getId());
        s.setRail(BidSupplementRail.STRIPE);
        s.setStatus(BidSupplementStatus.PAID);
        s.setCurrency("EUR");
        s.setNetAmount(new BigDecimal("20.00"));
        s.setCommissionAmount(new BigDecimal("1.10"));
        s.setGrossAmount(new BigDecimal("21.10"));
        s.setStripePaymentIntentId("pi_1");
        s.setStripeChargeId("ch_1");
        lenient().when(supplements.findBidIdById(s.getId())).thenReturn(Optional.of(bid.getId()));
        lenient().when(supplements.findByIdAndBidIdForUpdate(s.getId(), bid.getId())).thenReturn(Optional.of(s));
        lenient().when(supplements.findById(s.getId())).thenReturn(Optional.of(s));
        lenient().when(supplements.findByBidIdAndRailAndStatus(bid.getId(), BidSupplementRail.STRIPE,
                BidSupplementStatus.PAID)).thenAnswer(inv -> s.getStatus() == BidSupplementStatus.PAID ? List.of(s) : List.of());
        lenient().when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        lenient().when(gateway.retrieveCharge(any())).thenReturn(new StripeSupplementGateway.ChargeView(false, 0L));
    }

    private PaymentReleasedEvent cardRelease() {
        return PaymentReleasedEvent.card(s.getBidId(), traveler.getId(), s.getSenderId(), new BigDecimal("50.00"),
                new BigDecimal("5.00"), "EUR");
    }

    @Test
    @SuppressWarnings("unchecked")
    void versementPrincipal_transferDuNetAdosseALaChargeDuSupplement() throws Exception {
        when(gateway.findTransferByGroup("supplement_" + s.getId())).thenReturn(Optional.empty());
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        when(gateway.createTransfer(eq(2000L), eq("eur"), eq("acct_traveler"), eq("ch_1"),
                eq("supplement_" + s.getId()), metadata.capture(), eq("supplement-transfer-" + s.getId())))
                .thenReturn("tr_1");

        listener.onPaymentReleased(cardRelease());

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID_OUT);
        assertThat(s.getStripeTransferId()).isEqualTo("tr_1");
        assertThat(metadata.getValue()).containsEntry("bid_supplement_id", s.getId().toString())
                .containsEntry("source", "supplement-release")
                .as("jamais pris pour le versement principal").doesNotContainKey("payment_id");
        verify(events).publishEvent(new BidSupplementPaidOutEvent(s.getId(), s.getBidId(), traveler.getId(),
                s.getNetAmount(), "EUR"));
    }

    @Test
    void evenementRejoue_unSeulTransfer() throws Exception {
        when(gateway.findTransferByGroup(anyString())).thenReturn(Optional.empty());
        when(gateway.createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap(),
                anyString())).thenReturn("tr_1");

        listener.onPaymentReleased(cardRelease());
        listener.onPaymentReleased(cardRelease());

        verify(gateway).createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap(),
                anyString());
    }

    @Test
    void reprise_transferDejaCreeChezStripe_retrouveParSonGroupe() throws Exception {
        s.setStatus(BidSupplementStatus.PAYOUT_PENDING);
        when(gateway.findTransferByGroup("supplement_" + s.getId())).thenReturn(Optional.of("tr_existant"));

        assertThat(payouts.resume(s.getId())).isTrue();

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID_OUT);
        assertThat(s.getStripeTransferId()).isEqualTo("tr_existant");
        verify(gateway, never()).createTransfer(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void chargeAbsente_relueSurLaPaymentIntent() throws Exception {
        s.setStripeChargeId(null);
        when(gateway.findTransferByGroup(anyString())).thenReturn(Optional.empty());
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(new PaymentIntentView("pi_1", "succeeded", 2110L,
                "eur", null, "ch_relue", null));
        when(gateway.createTransfer(anyLong(), anyString(), anyString(), eq("ch_relue"), anyString(), anyMap(),
                anyString())).thenReturn("tr_1");

        assertThat(payouts.payout(s.getId())).isTrue();
        assertThat(s.getStripeChargeId()).isEqualTo("ch_relue");
    }

    @Test
    void retenueDuVoyageur_resteAPayeEtAlerte() {
        when(holdPolicy.isHeld(traveler.getId())).thenReturn(true);

        listener.onPaymentReleased(cardRelease());

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID);
        verify(alerts).raiseOnce(eq("SUPP_PAYOUT_BLOCKED_" + s.getId()), anyString(), anyMap());
        verifyNoInteractions(gateway);
    }

    @Test
    void litigeOuvert_ouCompteConnectAbsent_resteAPaye() {
        when(disputes.existsByBidIdAndStatusAndTypeStartingWith(s.getBidId(), "OPEN", "ADMIN_")).thenReturn(true);
        assertThat(payouts.payout(s.getId())).isFalse();

        when(disputes.existsByBidIdAndStatusAndTypeStartingWith(s.getBidId(), "OPEN", "ADMIN_")).thenReturn(false);
        traveler.setStripeAccountId(" ");
        assertThat(payouts.payout(s.getId())).isFalse();

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID);
        verify(alerts, org.mockito.Mockito.times(2)).raiseOnce(startsWith("SUPP_PAYOUT_BLOCKED_"), anyString(), anyMap());
        verifyNoInteractions(gateway);
    }

    @Test
    void echecDuTransfer_resteAPayeEtAlerte() throws Exception {
        when(gateway.findTransferByGroup(anyString())).thenReturn(Optional.empty());
        when(gateway.createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap(),
                anyString())).thenThrow(new ApiConnectionException("timeout"));

        assertThat(payouts.payout(s.getId())).isFalse();

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID);
        verify(alerts).raiseOnce(eq("SUPP_TRANSFER_FAILED_" + s.getId()), anyString(), anyMap());
        verify(events, never()).publishEvent(any(BidSupplementPaidOutEvent.class));
    }

    @Test
    void chargeIntrouvable_ouCompteRetireEntreTemps_echecSansTransfer() throws Exception {
        s.setStripeChargeId(null);
        s.setStripePaymentIntentId(null);
        when(gateway.findTransferByGroup(anyString())).thenReturn(Optional.empty());
        assertThat(payouts.payout(s.getId())).isFalse();

        s.setStatus(BidSupplementStatus.PAYOUT_PENDING);
        traveler.setStripeAccountId(null);
        when(supplements.findById(s.getId())).thenReturn(Optional.of(s));
        assertThat(payouts.resume(s.getId())).isFalse();
        verify(gateway, never()).createTransfer(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void mobileMoney_supplementInconnuOuNonPaye_rienAVerser() {
        listener.onPaymentReleased(new PaymentReleasedEvent(s.getBidId(), traveler.getId(), s.getSenderId(),
                new BigDecimal("1000"), "XOF", true));
        s.setStatus(BidSupplementStatus.PAID_OUT);
        assertThat(payouts.payout(s.getId())).isFalse();
        assertThat(payouts.payout(UUID.randomUUID())).isFalse();
        verifyNoInteractions(gateway, alerts);
    }

    @Test
    void chargeDuSupplementContestee_ouPartiellementRemboursee_versementRetenu() throws Exception {
        when(gateway.retrieveCharge("ch_1")).thenReturn(new StripeSupplementGateway.ChargeView(true, 0L),
                new StripeSupplementGateway.ChargeView(false, 500L));

        assertThat(payouts.payout(s.getId())).isFalse();
        assertThat(payouts.payout(s.getId())).isFalse();

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID);
        verify(alerts, org.mockito.Mockito.times(2)).raiseOnce(eq("SUPP_PAYOUT_BLOCKED_" + s.getId()), anyString(),
                anyMap());
        verify(gateway, never()).createTransfer(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void lectureDeLaChargeImpossible_versementRetenu() throws Exception {
        when(gateway.retrieveCharge("ch_1")).thenThrow(new ApiConnectionException("timeout"));
        assertThat(payouts.payout(s.getId())).isFalse();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID);
        verify(gateway, never()).createTransfer(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void compteConnectPlusValide_versementRetenu() throws Exception {
        traveler.setStripeAccountStatus(com.yadony.api.auth.StripeAccountStatus.DISABLED);
        assertThat(payouts.payout(s.getId())).isFalse();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID);
        verify(alerts).raiseOnce(eq("SUPP_PAYOUT_BLOCKED_" + s.getId()), anyString(), anyMap());
        verify(gateway, never()).createTransfer(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void transferPartiMaisNonEnregistre_resteEnVersementEtAlerteDediee() throws Exception {
        when(gateway.findTransferByGroup(anyString())).thenReturn(Optional.empty());
        when(gateway.createTransfer(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap(),
                anyString())).thenReturn("tr_1");
        when(supplements.save(s)).thenReturn(s).thenThrow(new IllegalStateException("base indisponible"));

        assertThat(payouts.payout(s.getId())).isFalse();

        // Jamais de retour à PAID (le Transfer repartirait) : la transaction annulée laisse
        // PAYOUT_PENDING en base, le filet retrouvera le Transfer par son groupe.
        verify(auditService, never()).log(any(), any(), eq("BID_SUPPLEMENT_PAYOUT_FAILED"), any(), anyMap());
        verify(alerts).raiseOnce(eq("SUPP_PAYOUT_UNRECORDED_" + s.getId()), org.mockito.ArgumentMatchers.contains("tr_1"),
                anyMap());
        verify(alerts, never()).raiseOnce(startsWith("SUPP_TRANSFER_FAILED_"), anyString(), anyMap());
    }

    @Test
    void groupeDuTransfer_distinctDuVersementPrincipal() {
        UUID id = UUID.randomUUID();
        assertThat(BidSupplementCardPayouts.transferGroup(id)).isEqualTo("supplement_" + id)
                .isNotEqualTo(com.yadony.api.payments.StripeTransferLookup.transferGroup(id));
    }
}
