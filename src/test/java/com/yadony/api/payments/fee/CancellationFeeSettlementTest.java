package com.yadony.api.payments.fee;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.currency.ExchangeRateLookup;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.wallet.fees.StripeFeeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Règlement de la retenue d'annulation côté paiements : rassemble montant payé, taux dû, plafond
 * converti, frais Stripe et commission, puis délègue le calcul à {@link CancellationFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class CancellationFeeSettlementTest {

    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID TRAVELER = UUID.randomUUID();
    private static final String PI = "pi_fee";

    @Mock CancellationFeeRatePort port;
    @Mock BidRepository bids;
    @Mock StripeFeeSource stripeFees;
    @Mock com.yadony.api.payments.wallet.fees.PawapayFeeTable pawapayFees;
    @Mock CommissionRateResolver commissionRates;
    @Mock ExchangeRateLookup rateLookup;
    @Mock ApplicationEventPublisher events;

    private CancellationFeeSettlement settlement;

    @BeforeEach
    void setUp() {
        settlement = new CancellationFeeSettlement(port, bids, stripeFees, pawapayFees, commissionRates,
                new ExchangeRateService(rateLookup), events);
        lenient().when(port.capEur()).thenReturn(new BigDecimal("30"));
        // Le règlement lit le taux sous verrou (dueRateForUpdate) ; ici le port répond comme sa lecture simple.
        org.mockito.Mockito.lenient().when(port.dueRateForUpdate(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> port.dueRate(inv.getArgument(0)));
    }

    @Test
    void compute_100eurPaidAt30Percent_matchesTheSpecExample() {
        // 100 € payés dont 4,76 € de commission (5 % du net 95,24 €) : taux effectif 0,049979.
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));

        CancellationFeeBreakdown b = settlement.compute(payment, SENDER, TRAVELER, new BigDecimal("0.30"));

        assertAmounts(b, "30.00", "1.75", "1.41", "26.84", "70.00", 2);
        verifyNoInteractions(commissionRates);
    }

    @Test
    void compute_stripeFeeUnreadable_usesTheConfiguredFallback() {
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        when(stripeFees.fee(PI, "EUR")).thenReturn(null);
        when(stripeFees.fallback(new BigDecimal("100.00"), "EUR")).thenReturn(new BigDecimal("3.40"));

        CancellationFeeBreakdown b = settlement.compute(payment, SENDER, TRAVELER, new BigDecimal("0.30"));

        // 30 − 3,40 = 26,60 ; commission 26,60 × 0,049979 = 1,33
        assertAmounts(b, "30.00", "3.40", "1.33", "25.27", "70.00", 2);
    }

    @Test
    void compute_xof_capConvertedDownAndAmountsInteger() {
        // 30 € = 19 678,71 F CFA, arrondi VERS LE BAS : jamais plus de 30 € d'équivalent.
        when(rateLookup.unitsPerEur("XOF")).thenReturn(new BigDecimal("655.957"));
        PaymentEntity payment = payment("100000", "4762", "XOF");
        when(stripeFees.fee(PI, "XOF")).thenReturn(null);
        when(stripeFees.fallback(new BigDecimal("100000"), "XOF")).thenReturn(new BigDecimal("3150"));

        CancellationFeeBreakdown b = settlement.compute(payment, SENDER, TRAVELER, new BigDecimal("0.30"));

        // 19 678 − 3 150 = 16 528 ; commission 16 528 × 0,050001 = 826 ; reste 15 702
        assertAmounts(b, "19678", "3150", "826", "15702", "80322", 0);
    }

    @Test
    void settle_honoursThePromoRateSnapshottedOnThePayment() {
        // Code promo : 2 % de commission réellement prélevée (2 € sur un net de 100 €), pas le taux du jour.
        PaymentEntity payment = payment("102.00", "2.00", "EUR");
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));

        Optional<CancellationFeeBreakdown> b = settlement.settle(payment, SENDER, TRAVELER);

        // 30,60 plafonné à 30 ; 28,25 × 0,02 = 0,57
        assertThat(b).hasValueSatisfying(fee -> assertAmounts(fee, "30.00", "1.75", "0.57", "27.68", "72.00", 2));
        verifyNoInteractions(commissionRates);
    }

    @Test
    void settle_voucherWaivedTheCommission_travelerGetsAllAfterFees() {
        PaymentEntity payment = payment("100.00", "0.00", "EUR");
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));

        Optional<CancellationFeeBreakdown> b = settlement.settle(payment, SENDER, TRAVELER);

        assertThat(b).hasValueSatisfying(fee -> assertAmounts(fee, "30.00", "1.75", "0.00", "28.25", "70.00", 2));
        verifyNoInteractions(commissionRates);
    }

    @Test
    void settle_noCommissionSnapshot_fallsBackToTheResolver() {
        PaymentEntity payment = payment("100.00", null, "EUR");
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));
        when(commissionRates.resolve(TRAVELER, SENDER)).thenReturn(new BigDecimal("0.05"));

        Optional<CancellationFeeBreakdown> b = settlement.settle(payment, SENDER, TRAVELER);

        assertThat(b).hasValueSatisfying(fee -> assertAmounts(fee, "30.00", "1.75", "1.41", "26.84", "70.00", 2));
    }

    @Test
    void settle_commissionNotBelowAmount_fallsBackToTheResolver() {
        // Instantané incohérent (net nul) : pas de division par zéro, taux du jour.
        PaymentEntity payment = payment("100.00", "100.00", "EUR");
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));
        when(commissionRates.resolve(TRAVELER, SENDER)).thenReturn(new BigDecimal("0.05"));

        assertThat(settlement.settle(payment, SENDER, TRAVELER))
                .hasValueSatisfying(fee -> assertThat(fee.commission()).isEqualByComparingTo("1.41"));
    }

    @Test
    void settle_refundedAmountZero_isNotAPriorRefund() {
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        payment.setRefundedAmount(new BigDecimal("0.00"));
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));

        assertThat(settlement.settle(payment, SENDER, TRAVELER)).isPresent();
    }

    @Test
    void settle_noFeeDue_emptyWithoutAnyStripeLookup() {
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.empty());

        assertThat(settlement.settle(payment, SENDER, TRAVELER)).isEmpty();
        verifyNoInteractions(stripeFees, commissionRates);
    }

    @Test
    void settle_threadPayment_readsTheFeeOfTheBidMaterializedFromTheThread() {
        UUID threadId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        payment.setBidId(null);
        payment.setNegotiationThreadId(threadId);
        when(bids.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid(bidId)));
        when(port.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));

        assertThat(settlement.settle(payment, SENDER, TRAVELER)).isPresent();
    }

    @Test
    void settle_paymentWithoutBidNorThread_empty() {
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        payment.setBidId(null);

        assertThat(settlement.settle(payment, SENDER, TRAVELER)).isEmpty();
        settlement.waiveIfDue(payment, "requires_capture");
        verifyNoInteractions(port, stripeFees);
    }

    @Test
    void legacyDestinationCharge_cannotCarryAFee_closedNotApplicable() {
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        payment.setLegacyDestinationCharge(true);

        assertThat(settlement.settle(payment, SENDER, TRAVELER)).isEmpty();
        verify(port, never()).dueRate(any());

        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("0.30")));
        settlement.waiveIfDue(payment, "succeeded");
        verify(port).markNotApplicable(payment.getBidId(), CancellationFeeSettlement.LEGACY_DESTINATION_CHARGE);
    }

    @Test
    void alreadyPartiallyRefunded_cannotCarryAFee_closedNotApplicable() {
        // Le remboursement complet ne rend plus que le reste : retenir dessus, c'est un montant à trancher.
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        payment.setRefundedAmount(new BigDecimal("10.00"));

        assertThat(settlement.settle(payment, SENDER, TRAVELER)).isEmpty();
        verify(port, never()).dueRate(any());

        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("0.30")));
        settlement.waiveIfDue(payment, "succeeded");
        verify(port).markNotApplicable(payment.getBidId(), CancellationFeeSettlement.ALREADY_PARTIALLY_REFUNDED);
    }

    @ParameterizedTest
    @CsvSource({
            "requires_capture, PAYMENT_NOT_CAPTURED",
            "requires_payment_method, PAYMENT_NOT_CAPTURED",
            "canceled, PAYMENT_INTENT_CANCELED"})
    void waiveIfDue_uncapturedOrCanceledIntent_closesWithReason(String piStatus, String reason) {
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("0.30")));

        settlement.waiveIfDue(payment, piStatus);

        verify(port).markNotApplicable(payment.getBidId(), reason);
    }

    @Test
    void waiveIfDue_nothingDue_noLockingWrite() {
        // Remboursement ordinaire sans retenue : une lecture simple, jamais le verrou de la clôture.
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.empty());

        settlement.waiveIfDue(payment, "requires_capture");

        verify(port, never()).markNotApplicable(any(), any());
    }

    @Test
    void waiveIfDue_capturedEligiblePayment_nothingWasDue_noCall() {
        // settle() vient de lire le taux pour ce cas : vide, il n'y avait rien à clore.
        settlement.waiveIfDue(payment("100.00", "4.76", "EUR"), "succeeded");

        verifyNoInteractions(port);
    }

    @Test
    void waiveAfterFailedComputation_closesTheDueFeeWithItsReason() {
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("1.5000")));

        settlement.waiveAfterFailedComputation(payment);

        verify(port).markNotApplicable(payment.getBidId(), "FEE_COMPUTATION_FAILED");
    }

    @Test
    void waiveAfterFailedComputation_nothingDue_noLockingWrite() {
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.empty());

        settlement.waiveAfterFailedComputation(payment);

        verify(port, never()).markNotApplicable(any(), any());
    }

    @Test
    void retain_marksRetainedThenPublishesTheEvent() {
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        CancellationFeeBreakdown fee = new CancellationFeeBreakdown(new BigDecimal("30.00"), new BigDecimal("1.75"),
                new BigDecimal("1.41"), new BigDecimal("26.84"), new BigDecimal("70.00"));

        settlement.retain(payment, fee);

        InOrder order = inOrder(port, events);
        order.verify(port).markRetained(payment.getBidId(), fee, "EUR");
        order.verify(events).publishEvent(new CancellationFeeRetainedEvent(payment.getBidId()));
    }

    // ── Rail mobile money : frais lus dans le barème pawaPay de l'opérateur du dépôt ──────────────────────────

    @Test
    void computeMobileMoney_readsThePawapayFeesOnWhatIsActuallyRefunded() {
        when(rateLookup.unitsPerEur("XOF")).thenReturn(new BigDecimal("655.957"));
        PaymentEntity payment = payment("65600", "3124", "XOF");
        // Le rendu (65 600 − 19 678 = 45 922) ne dépend pas des frais : c'est sur lui que pawaPay facture.
        when(pawapayFees.partialRefundFee(eq("ORANGE_SEN"), argThat(a -> a.compareTo(new BigDecimal("65600")) == 0),
                argThat(a -> a.compareTo(new BigDecimal("45922")) == 0), eq("XOF"))).thenReturn(new BigDecimal("1443"));

        CancellationFeeBreakdown b = settlement.computeMobileMoney(payment, "ORANGE_SEN", new BigDecimal("0.30"));

        assertAmounts(b, "19678", "1443", "912", "17323", "45922", 0);
        verifyNoInteractions(stripeFees, commissionRates);
    }

    @Test
    void settleMobileMoney_dueFee_computedWithTheDepositOperator() {
        PaymentEntity payment = payment("50000", "2381", "XOF");
        when(rateLookup.unitsPerEur("XOF")).thenReturn(new BigDecimal("655.957"));
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(pawapayFees.partialRefundFee(eq("MTN_MOMO_CIV"), any(), any(), eq("XOF"))).thenReturn(new BigDecimal("1950"));

        Optional<CancellationFeeBreakdown> b = settlement.settleMobileMoney(payment, "MTN_MOMO_CIV", new BigDecimal("50000"));

        assertThat(b).hasValueSatisfying(fee -> assertAmounts(fee, "15000", "1950", "653", "12397", "35000", 0));
    }

    @Test
    void settleMobileMoney_depositOfAnotherAmount_cannotCarryAFee_closedNotApplicable() {
        PaymentEntity payment = payment("65600", "3124", "XOF");
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("0.30")));

        assertThat(settlement.settleMobileMoney(payment, "ORANGE_SEN", new BigDecimal("65000"))).isEmpty();
        settlement.waiveMobileMoneyIfDue(payment, new BigDecimal("65000"));

        verify(port).markNotApplicable(payment.getBidId(), "DEPOSIT_AMOUNT_MISMATCH");
        verifyNoInteractions(pawapayFees);
    }

    @Test
    void settleMobileMoney_noFeeDue_emptyWithoutReadingTheFees() {
        PaymentEntity payment = payment("65600", "3124", "XOF");

        assertThat(settlement.settleMobileMoney(payment, "ORANGE_SEN", new BigDecimal("65600"))).isEmpty();
        settlement.waiveMobileMoneyIfDue(payment, new BigDecimal("65600"));

        verifyNoInteractions(pawapayFees, rateLookup);
        verify(port, never()).markNotApplicable(any(), any());
    }

    @Test
    void waiveAfterRefundInFlight_andNotCollected_closeTheDueFeeWithTheirReason() {
        PaymentEntity payment = payment("65600", "3124", "XOF");
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("0.30")));

        settlement.waiveAfterRefundInFlight(payment);
        settlement.waiveNotCollected(payment);

        verify(port).markNotApplicable(payment.getBidId(), "REFUND_ALREADY_IN_FLIGHT");
        verify(port).markNotApplicable(payment.getBidId(), "PAYMENT_NOT_CAPTURED");
    }

    @Test
    void waiveNotCollected_nothingDue_noLockingWrite() {
        PaymentEntity payment = payment("65600", "3124", "XOF");

        settlement.waiveNotCollected(payment);

        verify(port, never()).markNotApplicable(any(), any());
    }

    // ── Aperçu montré à l'expéditeur avant d'annuler : même brut, même remboursé que le règlement ─────────────

    @Test
    void preview_capturedCard_sameGrossAndRefundAsTheSettlement() {
        PaymentEntity payment = captured(payment("100.00", "4.76", "EUR"));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));

        CancellationFeeSettlement.Preview preview = settlement.preview(payment, new BigDecimal("0.20"), null);
        CancellationFeeBreakdown settled = settlement.compute(payment, SENDER, TRAVELER, new BigDecimal("0.20"));

        assertThat(preview.retainable()).isTrue();
        assertThat(preview.grossFee()).isEqualTo(settled.grossFee()).isEqualByComparingTo("20.00");
        assertThat(preview.senderRefund()).isEqualTo(settled.senderRefund()).isEqualByComparingTo("80.00");
    }

    @Test
    void preview_capped_trueWhenTheCapLimitsTheGross_falseOtherwise_sameGrossAsTheSettlement() {
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));
        PaymentEntity big = captured(payment("200.00", "9.52", "EUR"));
        PaymentEntity small = captured(payment("100.00", "4.76", "EUR"));
        BigDecimal rate = new BigDecimal("0.20");

        CancellationFeeSettlement.Preview capped = settlement.preview(big, rate, null);
        CancellationFeeSettlement.Preview notCapped = settlement.preview(small, rate, null);

        assertThat(capped.capped()).isTrue();
        assertThat(capped.grossFee()).isEqualByComparingTo("30.00")
                .isEqualTo(settlement.compute(big, SENDER, TRAVELER, rate).grossFee());
        assertThat(notCapped.capped()).isFalse();
        assertThat(notCapped.grossFee()).isEqualByComparingTo("20.00");
    }

    @Test
    void preview_capped_xofConvertedCap() {
        when(rateLookup.unitsPerEur("XOF")).thenReturn(new BigDecimal("655.957"));

        assertThat(settlement.preview(captured(payment("100000", "4762", "XOF")), new BigDecimal("0.30"), null)
                .capped()).isTrue();
        assertThat(settlement.preview(captured(payment("65600", "3124", "XOF")), new BigDecimal("0.20"), null)
                .capped()).isFalse();
    }

    @Test
    void preview_capped_falseWithoutFee() {
        assertThat(settlement.preview(captured(payment("100000", "4762", "XOF")), BigDecimal.ZERO, null).capped())
                .isFalse();
        assertThat(settlement.preview(payment("200.00", "9.52", "EUR"), new BigDecimal("0.30"), null).capped())
                .as("carte non capturée : pas de retenue").isFalse();
    }

    @Test
    void preview_neverReadsStripeFeesNorTheCommission() {
        PaymentEntity payment = captured(payment("100.00", "4.76", "EUR"));

        settlement.preview(payment, new BigDecimal("0.30"), null);

        verifyNoInteractions(stripeFees, pawapayFees, commissionRates);
    }

    @Test
    void preview_xof_capConvertedDownLikeTheSettlement() {
        when(rateLookup.unitsPerEur("XOF")).thenReturn(new BigDecimal("655.957"));
        PaymentEntity payment = captured(payment("100000", "4762", "XOF"));

        CancellationFeeSettlement.Preview preview = settlement.preview(payment, new BigDecimal("0.30"), null);

        assertThat(preview.grossFee()).isEqualByComparingTo("19678");
        assertThat(preview.grossFee().scale()).isZero();
        assertThat(preview.senderRefund()).isEqualByComparingTo("80322");
    }

    @Test
    void preview_rateZero_retainableButNothingRetained_noExchangeRateRead() {
        PaymentEntity payment = captured(payment("100000", "4762", "XOF"));

        CancellationFeeSettlement.Preview preview = settlement.preview(payment, BigDecimal.ZERO, null);

        assertThat(preview.retainable()).isTrue();
        assertThat(preview.grossFee()).isEqualByComparingTo("0");
        assertThat(preview.senderRefund()).isEqualByComparingTo("100000");
        verifyNoInteractions(rateLookup);
    }

    @Test
    void preview_cardNotCaptured_cannotCarryAFee_likeTheSettlementWhichCancelsTheHold() {
        assertNotRetainable(settlement.preview(payment("100.00", "4.76", "EUR"), new BigDecimal("0.30"), null), "100.00");
    }

    @Test
    void preview_legacyOrAlreadyPartiallyRefunded_cannotCarryAFee() {
        PaymentEntity legacy = captured(payment("100.00", "4.76", "EUR"));
        legacy.setLegacyDestinationCharge(true);
        PaymentEntity partial = captured(payment("100.00", "4.76", "EUR"));
        partial.setRefundedAmount(new BigDecimal("10.00"));

        assertNotRetainable(settlement.preview(legacy, new BigDecimal("0.30"), null), "100.00");
        assertNotRetainable(settlement.preview(partial, new BigDecimal("0.30"), null), "100.00");
    }

    @Test
    void preview_notInEscrow_cannotCarryAFee() {
        PaymentEntity released = captured(payment("100.00", "4.76", "EUR"));
        released.setStatus(PaymentStatus.RELEASED);

        assertNotRetainable(settlement.preview(released, new BigDecimal("0.30"), null), "100.00");
    }

    @Test
    void preview_mobileMoney_depositOfThePaymentAmount_sameGrossAsTheSettlement() {
        when(rateLookup.unitsPerEur("XOF")).thenReturn(new BigDecimal("655.957"));
        PaymentEntity payment = mobileMoney(payment("65600", "3124", "XOF"));
        when(pawapayFees.partialRefundFee(eq("ORANGE_SEN"), any(), any(), eq("XOF"))).thenReturn(new BigDecimal("1443"));

        CancellationFeeSettlement.Preview preview = settlement.preview(payment, new BigDecimal("0.30"),
                new BigDecimal("65600"));
        CancellationFeeBreakdown settled = settlement.computeMobileMoney(payment, "ORANGE_SEN", new BigDecimal("0.30"));

        assertThat(preview.retainable()).isTrue();
        assertThat(preview.grossFee()).isEqualTo(settled.grossFee()).isEqualByComparingTo("19678");
        assertThat(preview.senderRefund()).isEqualTo(settled.senderRefund()).isEqualByComparingTo("45922");
    }

    @Test
    void preview_mobileMoney_noCompletedDepositOrAnotherAmount_cannotCarryAFee() {
        PaymentEntity payment = mobileMoney(payment("65600", "3124", "XOF"));

        assertNotRetainable(settlement.preview(payment, new BigDecimal("0.30"), null), "65600");
        assertNotRetainable(settlement.preview(payment, new BigDecimal("0.30"), new BigDecimal("65000")), "65600");
    }

    @Test
    void preview_computationFails_noFeeShown_likeTheSettlementWhichRefundsInFull() {
        // Taux de change absent : le règlement rembourserait en entier (échec du calcul jamais bloquant).
        PaymentEntity payment = captured(payment("100000", "4762", "XOF"));
        when(rateLookup.unitsPerEur("XOF")).thenThrow(new IllegalStateException("exchange-rate-missing"));

        assertNotRetainable(settlement.preview(payment, new BigDecimal("0.30"), null), "100000");
    }

    // ── Remboursement intégral par l'administration (rail carte, hors processRefund) ───────────────────────────

    @Test
    void waiveAfterAdminFullRefund_closesTheDueFeeWithItsReason() {
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        when(port.dueRate(payment.getBidId())).thenReturn(Optional.of(new BigDecimal("0.30")));

        settlement.waiveAfterAdminFullRefund(payment);

        verify(port).markNotApplicable(payment.getBidId(), "ADMIN_FULL_REFUND");
    }

    @Test
    void waiveAfterAdminFullRefund_threadPayment_closesTheFeeOfTheMaterializedBid() {
        UUID threadId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        PaymentEntity payment = payment("100.00", "4.76", "EUR");
        payment.setBidId(null);
        payment.setNegotiationThreadId(threadId);
        when(bids.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid(bidId)));
        when(port.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));

        settlement.waiveAfterAdminFullRefund(payment);

        verify(port).markNotApplicable(bidId, "ADMIN_FULL_REFUND");
    }

    @Test
    void waiveAfterAdminFullRefund_nothingDue_noLockingWrite() {
        PaymentEntity payment = payment("100.00", "4.76", "EUR");

        settlement.waiveAfterAdminFullRefund(payment);

        verify(port, never()).markNotApplicable(any(), any());
    }

    private static PaymentEntity captured(PaymentEntity payment) {
        payment.setCapturedAt(java.time.Instant.parse("2026-11-20T10:00:00Z"));
        return payment;
    }

    private static PaymentEntity mobileMoney(PaymentEntity payment) {
        payment.setRail(com.yadony.api.payments.PaymentRail.PAWAPAY);
        return payment;
    }

    private static void assertNotRetainable(CancellationFeeSettlement.Preview preview, String paid) {
        assertThat(preview.retainable()).isFalse();
        assertThat(preview.grossFee()).isEqualByComparingTo("0");
        assertThat(preview.senderRefund()).isEqualByComparingTo(paid);
    }

    /** Colis matérialisé depuis un fil de négociation. */
    private static BidEntity bid(UUID id) {
        BidEntity bid = new BidEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(bid, "id", id);
        return bid;
    }

    private static PaymentEntity payment(String amount, String commission, String currency) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setStripePaymentIntentId(PI);
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(commission == null ? null : new BigDecimal(commission));
        p.setCurrency(currency);
        p.setStatus(PaymentStatus.ESCROW);
        return p;
    }

    private static void assertAmounts(CancellationFeeBreakdown b, String gross, String refundFees, String commission,
                                      String travelerNet, String senderRefund, int scale) {
        assertThat(b.grossFee()).isEqualByComparingTo(gross);
        assertThat(b.refundFees()).isEqualByComparingTo(refundFees);
        assertThat(b.commission()).isEqualByComparingTo(commission);
        assertThat(b.travelerNet()).isEqualByComparingTo(travelerNet);
        assertThat(b.senderRefund()).isEqualByComparingTo(senderRefund);
        assertThat(b.grossFee().scale()).isEqualTo(scale);
        assertThat(b.travelerNet().scale()).isEqualTo(scale);
        assertThat(b.senderRefund().scale()).isEqualTo(scale);
    }
}
