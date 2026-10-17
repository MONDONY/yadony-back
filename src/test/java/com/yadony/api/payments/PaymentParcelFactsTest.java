package com.yadony.api.payments;

import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelLink;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentParcelFactsTest {

    private final BidRepository bids = mock(BidRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PawapayOperationRepository operations = mock(PawapayOperationRepository.class);
    private final PaymentParcelFacts facts = new PaymentParcelFacts(bids, payments, operations);

    private ParcelFacts factsFor(BidEntity bid, PaymentEntity payment, ParcelRole role) {
        when(bids.findById(any())).thenReturn(Optional.of(bid));
        when(payments.findForBid(any())).thenReturn(Optional.ofNullable(payment));
        return facts.facts(new ParcelRef(UUID.randomUUID(), UUID.randomUUID(), role, bid.getStatus().name(),
                null, null, null, null, true));
    }

    private static BidEntity bid(PaymentMethod method) {
        BidEntity b = new BidEntity();
        b.setStatus(BidStatus.IN_TRANSIT);
        b.setPaymentMethod(method);
        return b;
    }

    private static PaymentEntity payment(PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setStatus(status);
        p.setAmount(new BigDecimal("45.00"));
        p.setCurrency("EUR");
        return p;
    }

    @Test
    void leDestinataireNApprendRienDuPaiement() {
        ParcelFacts f = facts.facts(new ParcelRef(UUID.randomUUID(), UUID.randomUUID(), ParcelRole.RECIPIENT,
                "IN_TRANSIT", null, null, null, null, true));
        assertThat(f.lines()).containsExactly("Payment details are visible only to the sender and the traveler.");
        verifyNoInteractions(payments);
    }

    @Test
    void expediteurCarteSelonLEtatDuPaiement() {
        assertThat(factsFor(bid(PaymentMethod.STRIPE), payment(PaymentStatus.ESCROW), ParcelRole.SENDER).lines())
                .containsExactly("Payment method: card.", "The sender has paid; Yadony holds the money until delivery "
                        + "is confirmed, and refunds it in full automatically if the parcel is cancelled before transit.");
        assertThat(factsFor(bid(PaymentMethod.STRIPE), payment(PaymentStatus.RELEASED), ParcelRole.SENDER).lines().get(1))
                .contains("no automatic refund");
        assertThat(factsFor(bid(PaymentMethod.STRIPE), payment(PaymentStatus.PENDING), ParcelRole.SENDER).lines().get(1))
                .contains("nothing has been charged");
        assertThat(factsFor(bid(PaymentMethod.STRIPE), payment(PaymentStatus.FAILED), ParcelRole.SENDER).lines().get(1))
                .contains("failed");
        assertThat(factsFor(bid(PaymentMethod.STRIPE), payment(PaymentStatus.CANCELLED), ParcelRole.SENDER).lines().get(1))
                .contains("cancelled");
        assertThat(factsFor(bid(PaymentMethod.STRIPE), null, ParcelRole.SENDER).lines().get(1))
                .isEqualTo("No payment has been made for this parcel yet.");
    }

    @Test
    void remboursementCarteAvecLeMontantRembourse() {
        PaymentEntity p = payment(PaymentStatus.REFUNDED);
        p.setRefundedAmount(new BigDecimal("40.00"));
        assertThat(factsFor(bid(PaymentMethod.STRIPE), p, ParcelRole.SENDER).lines().get(1))
                .startsWith("The sender was refunded 40.00 EUR to the card used");
        p.setRefundedAmount(null);
        assertThat(factsFor(bid(PaymentMethod.STRIPE), p, ParcelRole.SENDER).lines().get(1))
                .startsWith("The sender was refunded 45.00 EUR");
    }

    @Test
    void remboursementMobileMoneySelonLOperation() {
        PaymentEntity p = payment(PaymentStatus.REFUNDED);
        p.setRail(PaymentRail.PAWAPAY);
        PawapayOperationEntity op = mock(PawapayOperationEntity.class);
        when(operations.findFirstByPaymentIdAndKindOrderByCreatedAtDesc(any(), eq(PawapayOperationKind.REFUND)))
                .thenReturn(Optional.of(op));

        when(op.getStatus()).thenReturn(PawapayOperationStatus.COMPLETED);
        assertThat(factsFor(bid(PaymentMethod.WAVE), p, ParcelRole.SENDER).lines())
                .containsExactly("Payment method: mobile money.",
                        "The sender was refunded 45.00 EUR to the mobile money account used.");
        when(op.getStatus()).thenReturn(PawapayOperationStatus.FAILED);
        ParcelFacts failed = factsFor(bid(PaymentMethod.WAVE), p, ParcelRole.SENDER);
        assertThat(failed.lines().get(1)).contains("refund failed");
        assertThat(failed.links()).containsExactly(ParcelLink.SUPPORT);
        when(op.getStatus()).thenReturn(PawapayOperationStatus.PROCESSING);
        assertThat(factsFor(bid(PaymentMethod.WAVE), p, ParcelRole.SENDER).lines().get(1)).contains("in progress");
    }

    @Test
    void voyageurVoitSonVersement() {
        PaymentEntity released = payment(PaymentStatus.RELEASED);
        released.setEscrowReleasedAt(LocalDateTime.of(2026, 10, 16, 8, 0));
        assertThat(factsFor(bid(PaymentMethod.STRIPE), released, ParcelRole.TRAVELER).lines().get(1))
                .contains("on 2026-10-16", "24 hours");
        released.setPayoutHeldAt(LocalDateTime.of(2026, 10, 16, 9, 0));
        ParcelFacts held = factsFor(bid(PaymentMethod.STRIPE), released, ParcelRole.TRAVELER);
        assertThat(held.lines()).anyMatch(l -> l.contains("on hold"));
        assertThat(held.links()).containsExactly(ParcelLink.SUPPORT);
        assertThat(factsFor(bid(PaymentMethod.STRIPE), payment(PaymentStatus.ESCROW), ParcelRole.TRAVELER).lines().get(1))
                .contains("paid once delivery is confirmed");
        assertThat(factsFor(bid(PaymentMethod.STRIPE), payment(PaymentStatus.REFUNDED), ParcelRole.TRAVELER).lines().get(1))
                .contains("no payment for this parcel");
        assertThat(factsFor(bid(PaymentMethod.STRIPE), payment(PaymentStatus.PENDING), ParcelRole.TRAVELER).lines().get(1))
                .contains("not completed");
    }

    @Test
    void especesRienARembourserEnLigneEtCommissionDuVoyageur() {
        assertThat(factsFor(bid(PaymentMethod.CASH), null, ParcelRole.SENDER).lines().get(0))
                .contains("cash", "nothing to refund online");
        BidEntity cash = bid(PaymentMethod.CASH);
        assertThat(factsFor(cash, null, ParcelRole.TRAVELER).lines()).hasSize(1);
        cash.setCommissionStatus(CommissionStatus.CHARGED);
        assertThat(factsFor(cash, null, ParcelRole.TRAVELER).lines().get(1)).contains("commission is paid");
        cash.setCommissionStatus(CommissionStatus.REFUNDED);
        cash.setCommissionChargedVia(CommissionChargedVia.WALLET);
        assertThat(factsFor(cash, null, ParcelRole.TRAVELER).lines().get(1)).isEqualTo(
                "The commission was refunded to the wallet balance.");
        cash.setCommissionChargedVia(CommissionChargedVia.CARD);
        assertThat(factsFor(cash, null, ParcelRole.TRAVELER).lines().get(1)).endsWith("to the card.");
        cash.setCommissionStatus(CommissionStatus.REFUND_FAILED);
        assertThat(factsFor(cash, null, ParcelRole.TRAVELER).links()).containsExactly(ParcelLink.SUPPORT);
        cash.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
        assertThat(factsFor(cash, null, ParcelRole.TRAVELER).lines().get(1)).contains("not paid yet");
    }

    @Test
    void colisInconnu() {
        when(bids.findById(any())).thenReturn(Optional.empty());
        assertThat(facts.facts(new ParcelRef(UUID.randomUUID(), UUID.randomUUID(), ParcelRole.SENDER, "PENDING",
                null, null, null, null, true))).isEqualTo(ParcelFacts.NONE);
    }
}
