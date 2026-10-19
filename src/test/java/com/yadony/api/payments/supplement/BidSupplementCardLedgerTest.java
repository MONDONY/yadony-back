package com.yadony.api.payments.supplement;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.supplement.BidSupplementCardLedger.PaidOutcome;
import com.yadony.api.payments.supplement.BidSupplementCardLedger.RefundDecision;
import com.yadony.api.payments.supplement.events.BidSupplementPaidEvent;
import com.yadony.api.payments.supplement.events.BidSupplementPaidOutEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.payments.supplement.BidSupplementPricingTest.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** États du supplément carte : transitions sous verrou, rejeux sans effet, paiements tardifs. */
@ExtendWith(MockitoExtension.class)
class BidSupplementCardLedgerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock BidSupplementRepository supplements;
    @Mock BidRepository bids;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher events;

    private BidSupplementCardLedger ledger;
    private BidSupplementEntity s;
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        ledger = new BidSupplementCardLedger(supplements, bids, auditService, events,
                new BidSupplementWindow(new BidSupplementProperties(), Clock.fixed(NOW, ZoneOffset.UTC)));
        bid = new BidEntity();
        setId(bid, UUID.randomUUID());
        bid.setStatus(BidStatus.ACCEPTED);
        s = new BidSupplementEntity();
        setId(s, UUID.randomUUID());
        s.setBidId(bid.getId());
        s.setSenderId(UUID.randomUUID());
        s.setTravelerId(UUID.randomUUID());
        s.setRail(BidSupplementRail.STRIPE);
        s.setStatus(BidSupplementStatus.PAYMENT_PENDING);
        s.setStripePiAttempt(1);
        s.setStripePaymentIntentId("pi_1");
        s.setCurrency("EUR");
        s.setNetAmount(new BigDecimal("20.00"));
        s.setCommissionAmount(new BigDecimal("1.10"));
        s.setGrossAmount(new BigDecimal("21.10"));
        lenient().when(supplements.findBidIdById(s.getId())).thenReturn(Optional.of(bid.getId()));
        lenient().when(supplements.findByIdAndBidIdForUpdate(s.getId(), bid.getId())).thenReturn(Optional.of(s));
        lenient().when(bids.findById(bid.getId())).thenReturn(Optional.of(bid));
    }

    @Test
    void premiereAcceptation_tentativeUn_etAudit() {
        s.setStatus(BidSupplementStatus.PROPOSED);
        s.setStripePiAttempt(0);
        ledger.startPayment(s);
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAYMENT_PENDING);
        assertThat(s.getStripePiAttempt()).isEqualTo(1);
        verify(auditService).log(eq("BID_SUPPLEMENT"), eq(s.getId()), eq("BID_SUPPLEMENT_ACCEPTED_CARD"),
                eq(s.getSenderId()), anyMap());

        ledger.startPayment(s);
        verify(supplements).save(s);
    }

    @Test
    void paiementReussi_attenteDePaiement_devientPayeEtPrevientLeVoyageur() {
        assertThat(ledger.markPaid(s.getId(), "pi_1", "ch_1", "webhook")).isEqualTo(PaidOutcome.PAID);

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID);
        assertThat(s.getStripeChargeId()).isEqualTo("ch_1");
        assertThat(s.getPaidAt()).isEqualTo(NOW_UTC);
        verify(bids).lockForUpdate(bid.getId());
        verify(events).publishEvent(new BidSupplementPaidEvent(s.getId(), bid.getId(), s.getSenderId(),
                s.getTravelerId(), BidSupplementRail.STRIPE, s.getGrossAmount(), "EUR"));
        verify(auditService).log(eq("BID_SUPPLEMENT"), eq(s.getId()), eq("BID_SUPPLEMENT_PAID"), eq(s.getSenderId()),
                anyMap());
    }

    @Test
    void paiementRejoue_aucunSecondEffet() {
        ledger.markPaid(s.getId(), "pi_1", "ch_1", "webhook");
        assertThat(ledger.markPaid(s.getId(), "pi_1", "ch_1", "webhook")).isEqualTo(PaidOutcome.ALREADY);
        verify(events).publishEvent(any(BidSupplementPaidEvent.class));
    }

    @Test
    void paiementReussiAvantQueLaCreationNeLEnregistre_retientLaPaymentIntent() {
        s.setStripePaymentIntentId(null);
        assertThat(ledger.markPaid(s.getId(), "pi_9", null, "webhook")).isEqualTo(PaidOutcome.PAID);
        assertThat(s.getStripePaymentIntentId()).isEqualTo("pi_9");
    }

    @Test
    void paiementSurColisTermine_remboursementImmediat() {
        bid.setStatus(BidStatus.PARCEL_REFUSED);

        assertThat(ledger.markPaid(s.getId(), "pi_1", "ch_1", "webhook")).isEqualTo(PaidOutcome.LATE_REFUND);

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.REFUND_PENDING);
        verify(events).publishEvent(new BidSupplementRefundRequestedEvent(s.getId(), bid.getId(),
                BidSupplementRail.STRIPE, BidSupplementStatus.REFUND_PENDING, "BID_ENDED"));
        verify(events, never()).publishEvent(any(BidSupplementPaidEvent.class));
    }

    @Test
    void paiementTardifSurDemandeClose_resteCloseEtDemandeLeRemboursement() {
        s.setStatus(BidSupplementStatus.EXPIRED);

        assertThat(ledger.markPaid(s.getId(), "pi_1", "ch_1", "cancel-refused")).isEqualTo(PaidOutcome.LATE_REFUND);
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.EXPIRED);
        assertThat(s.getPaidAt()).isEqualTo(NOW_UTC);
        verify(events).publishEvent(new BidSupplementRefundRequestedEvent(s.getId(), bid.getId(),
                BidSupplementRail.STRIPE, BidSupplementStatus.EXPIRED, "LATE_PAYMENT"));

        assertThat(ledger.markPaid(s.getId(), "pi_1", "ch_1", "webhook")).isEqualTo(PaidOutcome.ALREADY);
    }

    @Test
    void paiementIgnore_inconnu_autreRail_ouAutrePaymentIntent() {
        UUID unknown = UUID.randomUUID();
        assertThat(ledger.markPaid(unknown, "pi_1", null, "webhook")).isEqualTo(PaidOutcome.IGNORED);

        assertThat(ledger.markPaid(s.getId(), "pi_autre", null, "webhook")).isEqualTo(PaidOutcome.IGNORED);
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAYMENT_PENDING);

        s.setRail(BidSupplementRail.PAWAPAY);
        assertThat(ledger.markPaid(s.getId(), "pi_1", null, "webhook")).isEqualTo(PaidOutcome.IGNORED);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void enregistrementDeLaPaymentIntent_seulementPourLaTentativeCourante() {
        s.setStripePaymentIntentId(null);
        assertThat(ledger.storePaymentIntent(s.getId(), 2, "pi_2").getStripePaymentIntentId()).isNull();
        assertThat(ledger.storePaymentIntent(s.getId(), 1, "pi_1").getStripePaymentIntentId()).isEqualTo("pi_1");
        assertThat(ledger.storePaymentIntent(s.getId(), 1, "pi_x").getStripePaymentIntentId()).isEqualTo("pi_1");
    }

    @Test
    void nouvelleTentative_seulementSiLaPaymentIntentAnnuleeEstToujoursCelleDuSupplement() {
        assertThat(ledger.renewAttempt(s.getId(), "pi_autre")).isFalse();
        assertThat(ledger.renewAttempt(s.getId(), "pi_1")).isTrue();
        assertThat(s.getStripePiAttempt()).isEqualTo(2);
        assertThat(s.getStripePaymentIntentId()).isNull();

        s.setStatus(BidSupplementStatus.PAID);
        assertThat(ledger.renewAttempt(s.getId(), null)).isFalse();
        assertThat(ledger.renewAttempt(UUID.randomUUID(), "pi_1")).isFalse();
    }

    @Test
    void versement_reservationExclusive_puisVerse() {
        s.setStatus(BidSupplementStatus.PAID);
        s.setStripeChargeId("ch_1");

        BidSupplementCardLedger.Claim claim = ledger.beginPayout(s.getId()).orElseThrow();
        assertThat(claim.net()).isEqualByComparingTo("20.00");
        assertThat(claim.chargeId()).isEqualTo("ch_1");
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAYOUT_PENDING);
        assertThat(ledger.beginPayout(s.getId())).as("second événement : pas de second versement").isEmpty();
        assertThat(ledger.pendingPayout(s.getId())).isEmpty();
        when(supplements.findById(s.getId())).thenReturn(Optional.of(s));
        assertThat(ledger.pendingPayout(s.getId())).isPresent();

        assertThat(ledger.completePayout(s.getId(), "tr_1", "ch_1")).isTrue();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID_OUT);
        assertThat(s.getStripeTransferId()).isEqualTo("tr_1");
        assertThat(s.getPaidOutAt()).isEqualTo(NOW_UTC);
        verify(events).publishEvent(new BidSupplementPaidOutEvent(s.getId(), bid.getId(), s.getTravelerId(),
                s.getNetAmount(), "EUR"));
        assertThat(ledger.completePayout(s.getId(), "tr_1", "ch_1")).isFalse();
    }

    @Test
    void versementEnEchec_retourAPaye() {
        s.setStatus(BidSupplementStatus.PAYOUT_PENDING);
        ledger.revertPayout(s.getId(), "boom");
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID);
        verify(auditService).log(eq("BID_SUPPLEMENT"), eq(s.getId()), eq("BID_SUPPLEMENT_PAYOUT_FAILED"), eq(null),
                anyMap());

        ledger.revertPayout(s.getId(), "rejeu");
        ledger.revertPayout(UUID.randomUUID(), "inconnu");
        verify(supplements).save(s);
    }

    @Test
    void versement_chargeRetrouveeEnregistreeSiAbsente() {
        s.setStatus(BidSupplementStatus.PAYOUT_PENDING);
        ledger.completePayout(s.getId(), "tr_1", "ch_9");
        assertThat(s.getStripeChargeId()).isEqualTo("ch_9");
    }

    @Test
    void versement_refuseHorsCarteOuNonPaye() {
        assertThat(ledger.beginPayout(s.getId())).isEmpty();
        s.setStatus(BidSupplementStatus.PAID);
        s.setRail(BidSupplementRail.PAWAPAY);
        assertThat(ledger.beginPayout(s.getId())).isEmpty();
        assertThat(ledger.beginPayout(UUID.randomUUID())).isEmpty();
    }

    @Test
    void remboursement_decisions() {
        s.setStatus(BidSupplementStatus.PAID);
        assertThat(ledger.beginRefund(s.getId()).decision()).isEqualTo(RefundDecision.PROCEED);
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.REFUND_PENDING);
        assertThat(ledger.beginRefund(s.getId()).decision()).as("reprise").isEqualTo(RefundDecision.PROCEED);

        s.setStatus(BidSupplementStatus.PAID_OUT);
        assertThat(ledger.beginRefund(s.getId()).decision()).isEqualTo(RefundDecision.AFTER_PAYOUT);
        s.setStatus(BidSupplementStatus.PAYOUT_PENDING);
        assertThat(ledger.beginRefund(s.getId()).decision()).isEqualTo(RefundDecision.AFTER_PAYOUT);

        s.setStatus(BidSupplementStatus.DECLINED);
        assertThat(ledger.beginRefund(s.getId()).decision()).as("refusé, jamais payé").isEqualTo(RefundDecision.NOTHING);
        s.setPaidAt(NOW_UTC);
        assertThat(ledger.beginRefund(s.getId()).decision()).as("paiement tardif").isEqualTo(RefundDecision.PROCEED);
        s.setRefundedAt(NOW_UTC);
        assertThat(ledger.beginRefund(s.getId()).decision()).isEqualTo(RefundDecision.NOTHING);

        s.setRail(BidSupplementRail.CASH);
        assertThat(ledger.beginRefund(s.getId()).decision()).isEqualTo(RefundDecision.NOTHING);
        assertThat(ledger.beginRefund(UUID.randomUUID()).decision()).isEqualTo(RefundDecision.NOTHING);
    }

    @Test
    void remboursementFait_rembourseEtPrevientLExpediteur_uneSeuleFois() {
        s.setStatus(BidSupplementStatus.REFUND_PENDING);

        assertThat(ledger.completeRefund(s.getId(), "re_1", "refund")).isTrue();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.REFUNDED);
        assertThat(s.getStripeRefundId()).isEqualTo("re_1");
        verify(events).publishEvent(new BidSupplementRefundedEvent(s.getId(), bid.getId(), s.getSenderId(),
                s.getGrossAmount(), "EUR", false));

        assertThat(ledger.completeRefund(s.getId(), "re_1", "webhook")).isFalse();
    }

    @Test
    void remboursementConstate_surPayeOuPaiementTardif_maisJamaisSurUneDemandeJamaisPayee() {
        s.setStatus(BidSupplementStatus.DECLINED);
        assertThat(ledger.completeRefund(s.getId(), "re_1", "webhook")).isFalse();
        s.setStatus(BidSupplementStatus.PAID_OUT);
        assertThat(ledger.completeRefund(s.getId(), "re_1", "webhook")).isFalse();

        s.setStatus(BidSupplementStatus.EXPIRED);
        s.setPaidAt(NOW_UTC);
        assertThat(ledger.completeRefund(s.getId(), "re_1", "refund")).isTrue();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.REFUNDED);

        BidSupplementEntity paid = new BidSupplementEntity();
        setId(paid, UUID.randomUUID());
        paid.setBidId(bid.getId());
        paid.setRail(BidSupplementRail.STRIPE);
        paid.setStatus(BidSupplementStatus.PAID);
        when(supplements.findBidIdById(paid.getId())).thenReturn(Optional.of(bid.getId()));
        when(supplements.findByIdAndBidIdForUpdate(paid.getId(), bid.getId())).thenReturn(Optional.of(paid));
        assertThat(ledger.completeRefund(paid.getId(), null, "webhook")).isTrue();
        assertThat(ledger.completeRefund(UUID.randomUUID(), null, "webhook")).isFalse();
    }

    @Test
    void auditSeul_etLectureFraiche() {
        when(supplements.findById(s.getId())).thenReturn(Optional.of(s));
        ledger.auditOnly(s.getId(), "BID_SUPPLEMENT_CHARGEBACK", Map.of("event", "charge.dispute.created"));
        verify(auditService).log(eq("BID_SUPPLEMENT"), eq(s.getId()), eq("BID_SUPPLEMENT_CHARGEBACK"), eq(null),
                anyMap());
        assertThat(ledger.find(s.getId())).contains(s);
    }
}
