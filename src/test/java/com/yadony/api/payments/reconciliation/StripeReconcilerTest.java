package com.yadony.api.payments.reconciliation;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.yadony.api.common.BaseEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StripeReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-10-09T04:30:00Z");

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final WalletTransactionRepository ledger = mock(WalletTransactionRepository.class);
    private final BidRepository bids = mock(BidRepository.class);
    private final StripeLedgerSource stripe = mock(StripeLedgerSource.class);
    private final StripeReconciler reconciler = new StripeReconciler(payments, ledger, bids, stripe);

    @BeforeEach
    void noDataByDefault() throws Exception {
        when(payments.findForStripeReconciliation(anyCollection(), any())).thenReturn(List.of());
        when(bids.findCardCommissionsUpdatedSince(any(), any())).thenReturn(List.of());
        when(ledger.findByTypeAndIdempotencyKeyStartingWithAndCreatedAtAfter(any(), any(), any()))
                .thenReturn(List.of());
        when(stripe.walletTopupsCreatedSince(any())).thenReturn(List.of());
    }

    // ── Paiements colis ──────────────────────────────────────────────────────

    private static PaymentEntity payment(PaymentStatus status, String amount, String piId) {
        PaymentEntity p = new PaymentEntity();
        ReflectionTestUtils.setField(p, BaseEntity.class, "id", UUID.randomUUID(), UUID.class);
        ReflectionTestUtils.setField(p, BaseEntity.class, "createdAt",
                LocalDateTime.ofInstant(NOW.minusSeconds(86_400), ZoneOffset.UTC), LocalDateTime.class);
        p.setStatus(status);
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(BigDecimal.ONE);
        p.setCurrency("EUR");
        p.setStripePaymentIntentId(piId);
        return p;
    }

    private static PaymentIntent intent(String id, String status, long amount, String currency, Long refunded) {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn(id);
        when(pi.getStatus()).thenReturn(status);
        when(pi.getAmount()).thenReturn(amount);
        when(pi.getCurrency()).thenReturn(currency);
        if (refunded != null) {
            Charge charge = mock(Charge.class);
            when(charge.getAmountRefunded()).thenReturn(refunded);
            when(pi.getLatestChargeObject()).thenReturn(charge);
        }
        return pi;
    }

    private void stripeHas(PaymentEntity p, PaymentIntent pi) throws Exception {
        when(payments.findForStripeReconciliation(anyCollection(), any())).thenReturn(List.of(p));
        when(stripe.retrieveWithLatestCharge(p.getStripePaymentIntentId())).thenReturn(pi);
    }

    @Test
    void sequestreAutoriseOuEncaisse_auBonMontant_estCoherent() throws Exception {
        PaymentEntity p = payment(PaymentStatus.ESCROW, "25.00", "pi_ok");
        stripeHas(p, intent("pi_ok", "requires_capture", 2500, "eur", null));

        ReconciliationResult result = reconciler.reconcile(NOW);

        assertThat(result.mismatches()).isEmpty();
        assertThat(result.checked()).isEqualTo(1);
    }

    @Test
    void sequestreChezNousMaisAnnuleChezStripe_estSignale() throws Exception {
        PaymentEntity p = payment(PaymentStatus.ESCROW, "25.00", "pi_gone");
        stripeHas(p, intent("pi_gone", "canceled", 2500, "eur", null));

        ReconciliationResult result = reconciler.reconcile(NOW);

        assertThat(result.mismatches()).singleElement()
                .satisfies(m -> {
                    assertThat(m.reference()).isEqualTo(p.getId().toString());
                    assertThat(m.code()).contains("SEQUESTRE_SANS_FONDS");
                });
    }

    @Test
    void annuleChezNousMaisEncaisseChezStripe_estSignale() throws Exception {
        PaymentEntity p = payment(PaymentStatus.CANCELLED, "25.00", "pi_paid");
        stripeHas(p, intent("pi_paid", "succeeded", 2500, "eur", 0L));

        assertThat(reconciler.reconcile(NOW).mismatches()).singleElement()
                .satisfies(m -> assertThat(m.code()).contains("ENCAISSE_MAIS_ANNULE"));
    }

    @Test
    void montantDifferent_estSignale() throws Exception {
        PaymentEntity p = payment(PaymentStatus.RELEASED, "25.00", "pi_amount");
        stripeHas(p, intent("pi_amount", "succeeded", 2400, "eur", 0L));

        assertThat(reconciler.reconcile(NOW).mismatches()).singleElement()
                .satisfies(m -> assertThat(m.code()).contains("MONTANT_DIFFERENT"));
    }

    @Test
    void remboursementStripeNonRepercute_estSignale() throws Exception {
        // 10 € remboursés chez Stripe (dashboard, litige) que la base ignore.
        PaymentEntity p = payment(PaymentStatus.RELEASED, "25.00", "pi_partial");
        stripeHas(p, intent("pi_partial", "succeeded", 2500, "eur", 1000L));

        assertThat(reconciler.reconcile(NOW).mismatches()).singleElement()
                .satisfies(m -> assertThat(m.code()).contains("REMBOURSE_DIFFERENT"));
    }

    @Test
    void rembourseChezNousMaisPasChezStripe_estSignale() throws Exception {
        PaymentEntity p = payment(PaymentStatus.REFUNDED, "25.00", "pi_norefund");
        p.setRefundedAmount(new BigDecimal("25.00"));
        stripeHas(p, intent("pi_norefund", "succeeded", 2500, "eur", 0L));

        assertThat(reconciler.reconcile(NOW).mismatches()).singleElement()
                .satisfies(m -> assertThat(m.code()).contains("REMBOURSEMENT_ABSENT_CHEZ_STRIPE"));
    }

    @Test
    void uneErreurStripe_estCompteeSansBloquerLesAutres() throws Exception {
        PaymentEntity broken = payment(PaymentStatus.ESCROW, "25.00", "pi_down");
        PaymentEntity fine = payment(PaymentStatus.ESCROW, "25.00", "pi_fine");
        when(payments.findForStripeReconciliation(anyCollection(), any())).thenReturn(List.of(broken, fine));
        when(stripe.retrieveWithLatestCharge("pi_down"))
                .thenThrow(new ApiConnectionException("Stripe injoignable"));
        PaymentIntent fineIntent = intent("pi_fine", "succeeded", 2500, "eur", 0L);
        when(stripe.retrieveWithLatestCharge("pi_fine")).thenReturn(fineIntent);

        ReconciliationResult result = reconciler.reconcile(NOW);

        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.mismatches()).isEmpty();
    }

    // ── Recharges wallet ─────────────────────────────────────────────────────

    private static PaymentIntent topup(String id, String status, long amount, Instant created) {
        PaymentIntent pi = intent(id, status, amount, "eur", null);
        when(pi.getCreated()).thenReturn(created.getEpochSecond());
        when(pi.getMetadata()).thenReturn(Map.of("wallet_topup", "true", "user_id", UUID.randomUUID().toString()));
        return pi;
    }

    private static WalletTransactionEntity credit(String piId, String amount) {
        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setType(WalletTransactionType.TOP_UP);
        tx.setCurrency("EUR");
        tx.setAmount(new BigDecimal(amount));
        tx.setPaymentRef(piId);
        tx.setIdempotencyKey("stripe-" + piId);
        return tx;
    }

    @Test
    void rechargePayeeMaisJamaisCreditee_estSignalee() throws Exception {
        PaymentIntent lost = topup("pi_topup_lost", "succeeded", 2000, NOW.minusSeconds(7_200));
        when(stripe.walletTopupsCreatedSince(any())).thenReturn(List.of(lost));
        when(ledger.findByIdempotencyKey("stripe-pi_topup_lost")).thenReturn(Optional.empty());

        assertThat(reconciler.reconcile(NOW).mismatches()).singleElement()
                .satisfies(m -> {
                    assertThat(m.reference()).isEqualTo("pi_topup_lost");
                    assertThat(m.code()).contains("RECHARGE_NON_CREDITEE");
                });
    }

    @Test
    void rechargeCrediteeAuBonMontant_estCoherente() throws Exception {
        PaymentIntent paid = topup("pi_topup_ok", "succeeded", 2000, NOW.minusSeconds(7_200));
        when(stripe.walletTopupsCreatedSince(any())).thenReturn(List.of(paid));
        when(ledger.findByIdempotencyKey("stripe-pi_topup_ok")).thenReturn(Optional.of(credit("pi_topup_ok", "20.00")));

        assertThat(reconciler.reconcile(NOW).mismatches()).isEmpty();
    }

    @Test
    void creditDeRechargeSansPaiementReussi_estSignale() throws Exception {
        WalletTransactionEntity orphan = credit("pi_never_paid", "20.00");
        when(ledger.findByTypeAndIdempotencyKeyStartingWithAndCreatedAtAfter(any(), any(), any()))
                .thenReturn(List.of(orphan));
        PaymentIntent unpaid = intent("pi_never_paid", "requires_payment_method", 2000, "eur", null);
        when(stripe.retrieveWithLatestCharge("pi_never_paid")).thenReturn(unpaid);

        assertThat(reconciler.reconcile(NOW).mismatches()).singleElement()
                .satisfies(m -> assertThat(m.code()).contains("CREDIT_SANS_PAIEMENT"));
    }

    // ── Commissions par carte ────────────────────────────────────────────────

    @Test
    void commissionMarqueePayeeMaisNonEncaissee_estSignalee() throws Exception {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setCommissionStatus(CommissionStatus.CHARGED);
        bid.setCommissionChargedVia(CommissionChargedVia.CARD);
        bid.setCommissionPaymentIntentId("pi_commission");
        when(bids.findCardCommissionsUpdatedSince(any(), any())).thenReturn(List.of(bid));
        PaymentIntent canceled = intent("pi_commission", "canceled", 300, "eur", null);
        when(stripe.retrieveWithLatestCharge("pi_commission")).thenReturn(canceled);

        assertThat(reconciler.reconcile(NOW).mismatches()).singleElement()
                .satisfies(m -> assertThat(m.code()).contains("COMMISSION_NON_ENCAISSEE"));
    }
}
