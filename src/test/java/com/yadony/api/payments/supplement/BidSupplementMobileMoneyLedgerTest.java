package com.yadony.api.payments.supplement;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.pawapay.PawapayProperties;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** États du supplément mobile money : transitions sous verrou, rejeux sans effet, paiements tardifs. */
@ExtendWith(MockitoExtension.class)
class BidSupplementMobileMoneyLedgerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock BidSupplementRepository supplements;
    @Mock BidRepository bids;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher events;

    private BidSupplementMobileMoneyLedger ledger;
    private BidSupplementEntity s;
    private BidEntity bid;

    static PawapayProperties pawapay(boolean enabled) {
        return new PawapayProperties(enabled, "https://api.sandbox.pawapay.io", "token", false, 30,
                "https://api.yadony.com", "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting", "yadony://payments/wallet", null);
    }

    private BidSupplementMobileMoneyLedger ledger(boolean enabled) {
        return new BidSupplementMobileMoneyLedger(supplements, bids, auditService, events,
                new BidSupplementWindow(new BidSupplementProperties(), Clock.fixed(NOW, ZoneOffset.UTC)),
                pawapay(enabled));
    }

    @BeforeEach
    void setUp() {
        ledger = ledger(true);
        bid = new BidEntity();
        setId(bid, UUID.randomUUID());
        bid.setStatus(BidStatus.ACCEPTED);
        s = new BidSupplementEntity();
        setId(s, UUID.randomUUID());
        s.setBidId(bid.getId());
        s.setSenderId(UUID.randomUUID());
        s.setTravelerId(UUID.randomUUID());
        s.setRail(BidSupplementRail.PAWAPAY);
        s.setStatus(BidSupplementStatus.PAYMENT_PENDING);
        s.setCurrency("XOF");
        s.setNetAmount(new BigDecimal("13000"));
        s.setCommissionAmount(new BigDecimal("700"));
        s.setGrossAmount(new BigDecimal("13700"));
        s.setExpiresAt(NOW_UTC.plusHours(1));
        lenient().when(supplements.findBidIdById(s.getId())).thenReturn(Optional.of(bid.getId()));
        lenient().when(supplements.findByIdAndBidIdForUpdate(s.getId(), bid.getId())).thenReturn(Optional.of(s));
        lenient().when(supplements.findById(s.getId())).thenReturn(Optional.of(s));
        lenient().when(bids.findById(bid.getId())).thenReturn(Optional.of(bid));
    }

    // ── Acceptation et préparation du dépôt ─────────────────────────────────

    @Test
    void acceptation_attenteDePaiement_etAudit() {
        s.setStatus(BidSupplementStatus.PROPOSED);
        ledger.startPayment(s);
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAYMENT_PENDING);
        verify(auditService).log(eq("BID_SUPPLEMENT"), eq(s.getId()), eq("BID_SUPPLEMENT_ACCEPTED_MM"),
                eq(s.getSenderId()), anyMap());
        // Rejeu : déjà en attente, rien de plus.
        ledger.startPayment(s);
        verify(supplements).save(s);
    }

    @Test
    void acceptation_railPawapayCoupe_422() {
        s.setStatus(BidSupplementStatus.PROPOSED);
        assertThatThrownBy(() -> ledger(false).startPayment(s))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("mobile-money-disabled"));
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PROPOSED);
    }

    @Test
    void preparation_verrouColisPuisDemande_etAcceptationDUneDemandeProposee() {
        s.setStatus(BidSupplementStatus.PROPOSED);
        BidSupplementMobileMoneyLedger.Claim claim = ledger.prepareDeposit(bid.getId(), s.getId(), s.getSenderId());
        verify(bids).lockForUpdate(bid.getId());
        assertThat(claim.status()).isEqualTo(BidSupplementStatus.PAYMENT_PENDING);
        assertThat(claim.gross()).isEqualByComparingTo("13700");
        assertThat(claim.expiresAt()).isEqualTo(s.getExpiresAt());
    }

    @Test
    void preparation_demandeProposeeEchue_409() {
        s.setStatus(BidSupplementStatus.PROPOSED);
        s.setExpiresAt(NOW_UTC.minusMinutes(1));
        assertThatThrownBy(() -> ledger.prepareDeposit(bid.getId(), s.getId(), s.getSenderId()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("supplement-expired"));
    }

    @Test
    void preparation_demandeDejaPayee_relueSansTransition() {
        s.setStatus(BidSupplementStatus.PAID);
        assertThat(ledger.prepareDeposit(bid.getId(), s.getId(), s.getSenderId()).status())
                .isEqualTo(BidSupplementStatus.PAID);
        verify(supplements, never()).save(any());
    }

    @Test
    void preparation_refusDesCasInvalides() {
        assertCode(() -> ledger.prepareDeposit(bid.getId(), s.getId(), UUID.randomUUID()), "forbidden");
        s.setStatus(BidSupplementStatus.DECLINED);
        assertCode(() -> ledger.prepareDeposit(bid.getId(), s.getId(), s.getSenderId()), "supplement-not-pending");
        s.setStatus(BidSupplementStatus.PAYMENT_PENDING);
        bid.setStatus(BidStatus.CANCELLED);
        assertCode(() -> ledger.prepareDeposit(bid.getId(), s.getId(), s.getSenderId()), "invalid-bid-status");
        s.setRail(BidSupplementRail.STRIPE);
        assertCode(() -> ledger.prepareDeposit(bid.getId(), s.getId(), s.getSenderId()), "supplement-rail-mismatch");
        UUID unknown = UUID.randomUUID();
        assertCode(() -> ledger.prepareDeposit(bid.getId(), unknown, s.getSenderId()), "supplement-not-found");
    }

    // ── Dépôt abouti ─────────────────────────────────────────────────────────

    @Test
    void depotAbouti_payeEtVoyageurPrevenu_rejeuSansEffet() {
        UUID depositId = UUID.randomUUID();
        assertThat(ledger.markPaid(s.getId(), depositId, "pawapay")).isEqualTo(PaidOutcome.PAID);
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID);
        assertThat(s.getPaidAt()).isEqualTo(NOW_UTC);
        verify(bids).lockForUpdate(bid.getId());
        verify(events).publishEvent(new BidSupplementPaidEvent(s.getId(), bid.getId(), s.getSenderId(),
                s.getTravelerId(), BidSupplementRail.PAWAPAY, s.getGrossAmount(), "XOF"));

        assertThat(ledger.markPaid(s.getId(), depositId, "poller")).isEqualTo(PaidOutcome.ALREADY);
        verify(events).publishEvent(any(BidSupplementPaidEvent.class));
    }

    @Test
    void depotAbouti_colisTermine_rembourseAussitot() {
        bid.setStatus(BidStatus.PARCEL_REFUSED);
        assertThat(ledger.markPaid(s.getId(), UUID.randomUUID(), "pawapay")).isEqualTo(PaidOutcome.LATE_REFUND);
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.REFUND_PENDING);
        verify(events).publishEvent(new BidSupplementRefundRequestedEvent(s.getId(), bid.getId(),
                BidSupplementRail.PAWAPAY, BidSupplementStatus.REFUND_PENDING, "BID_ENDED"));
    }

    @Test
    void depotAbouti_demandeClose_paiementTardifRendu() {
        s.setStatus(BidSupplementStatus.EXPIRED);
        assertThat(ledger.markPaid(s.getId(), UUID.randomUUID(), "pawapay")).isEqualTo(PaidOutcome.LATE_REFUND);
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.EXPIRED);
        assertThat(s.getPaidAt()).isEqualTo(NOW_UTC);
        verify(events).publishEvent(new BidSupplementRefundRequestedEvent(s.getId(), bid.getId(),
                BidSupplementRail.PAWAPAY, BidSupplementStatus.EXPIRED, "LATE_PAYMENT"));
    }

    @Test
    void depotAbouti_supplementInconnuOuAutreRail_ignore() {
        UUID unknown = UUID.randomUUID();
        assertThat(ledger.markPaid(unknown, UUID.randomUUID(), "pawapay")).isEqualTo(PaidOutcome.IGNORED);
        s.setRail(BidSupplementRail.STRIPE);
        assertThat(ledger.markPaid(s.getId(), UUID.randomUUID(), "pawapay")).isEqualTo(PaidOutcome.IGNORED);
    }

    // ── Versement ────────────────────────────────────────────────────────────

    @Test
    void versement_reserveConfirmeEtVoyageurPrevenu() {
        s.setStatus(BidSupplementStatus.PAID);
        assertThat(ledger.beginPayout(s.getId())).isPresent();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAYOUT_PENDING);
        assertThat(ledger.beginPayout(s.getId())).as("réservation exclusive").isEmpty();
        assertThat(ledger.pendingPayout(s.getId())).isPresent();

        UUID payoutId = UUID.randomUUID();
        assertThat(ledger.completePayout(s.getId(), payoutId)).isTrue();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID_OUT);
        assertThat(s.getPaidOutAt()).isEqualTo(NOW_UTC);
        verify(events).publishEvent(new BidSupplementPaidOutEvent(s.getId(), bid.getId(), s.getTravelerId(),
                s.getNetAmount(), "XOF"));
        assertThat(ledger.completePayout(s.getId(), payoutId)).as("rejeu").isFalse();
    }

    @Test
    void versementEchoue_retourAPaye_uneSeuleFois() {
        s.setStatus(BidSupplementStatus.PAYOUT_PENDING);
        assertThat(ledger.revertPayout(s.getId(), "PAYOUT_FAILED")).isTrue();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID);
        assertThat(ledger.revertPayout(s.getId(), "PAYOUT_FAILED")).isFalse();
    }

    // ── Remboursement ────────────────────────────────────────────────────────

    @Test
    void remboursement_decisionsSelonLeStatut() {
        s.setStatus(BidSupplementStatus.PAID);
        assertThat(ledger.beginRefund(s.getId()).decision()).isEqualTo(RefundDecision.PROCEED);
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.REFUND_PENDING);
        assertThat(ledger.beginRefund(s.getId()).decision()).as("reprise").isEqualTo(RefundDecision.PROCEED);

        s.setStatus(BidSupplementStatus.PAID_OUT);
        assertThat(ledger.beginRefund(s.getId()).decision()).isEqualTo(RefundDecision.AFTER_PAYOUT);

        s.setStatus(BidSupplementStatus.DECLINED);
        assertThat(ledger.beginRefund(s.getId()).decision()).isEqualTo(RefundDecision.NOTHING);
        s.setPaidAt(NOW_UTC);
        assertThat(ledger.beginRefund(s.getId()).decision()).as("paiement tardif").isEqualTo(RefundDecision.PROCEED);

        s.setRail(BidSupplementRail.STRIPE);
        assertThat(ledger.beginRefund(s.getId()).decision()).isEqualTo(RefundDecision.NOTHING);
    }

    @Test
    void remboursementConfirme_rembourseEtExpediteurPrevenu_rejeuSansEffet() {
        s.setStatus(BidSupplementStatus.REFUND_PENDING);
        UUID refundId = UUID.randomUUID();
        assertThat(ledger.completeRefund(s.getId(), refundId, "pawapay")).isTrue();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.REFUNDED);
        assertThat(s.getRefundedAt()).isEqualTo(NOW_UTC);
        verify(events).publishEvent(new BidSupplementRefundedEvent(s.getId(), bid.getId(), s.getSenderId(),
                s.getGrossAmount(), "XOF", false));
        assertThat(ledger.completeRefund(s.getId(), refundId, "pawapay")).isFalse();
    }

    @Test
    void remboursementConfirme_paiementTardifOuStatutNonRemboursable() {
        s.setStatus(BidSupplementStatus.CANCELLED);
        assertThat(ledger.completeRefund(s.getId(), UUID.randomUUID(), "pawapay")).as("jamais payé").isFalse();
        s.setPaidAt(NOW_UTC);
        assertThat(ledger.completeRefund(s.getId(), UUID.randomUUID(), "pawapay")).isTrue();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.REFUNDED);
    }

    @Test
    void auditSeul() {
        ledger.auditOnly(s.getId(), "BID_SUPPLEMENT_MM_DEPOSIT_FAILED", Map.of("failureCode", "X"));
        verify(auditService).log(eq("BID_SUPPLEMENT"), eq(s.getId()), eq("BID_SUPPLEMENT_MM_DEPOSIT_FAILED"),
                eq(null), anyMap());
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
