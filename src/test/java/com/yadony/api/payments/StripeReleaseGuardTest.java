package com.yadony.api.payments;

import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Gardes revérifiées juste avant l'appel Stripe ; dérogation admin garde par garde, jamais globale. */
class StripeReleaseGuardTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final StripeReleaseIntentRepository intents = mock(StripeReleaseIntentRepository.class);
    private final BidRepository bids = mock(BidRepository.class);
    private final DisputeRepository disputes = mock(DisputeRepository.class);
    private final PayoutHoldPolicy holds = mock(PayoutHoldPolicy.class);
    private final StripeReleaseGuard guard = new StripeReleaseGuard(payments, intents, bids, disputes, holds);

    private final UUID paymentId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private StripeReleaseIntentEntity intent;
    private PaymentEntity payment;
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        intent = new StripeReleaseIntentEntity();
        ReflectionTestUtils.setField(intent, "id", UUID.randomUUID());
        intent.setPaymentId(paymentId);
        intent.setBidId(bidId);
        intent.setTravelerId(travelerId);
        intent.setStatus(StripeReleaseIntentEntity.Status.PENDING);
        when(intents.findById(intent.getId())).thenReturn(Optional.of(intent));
        payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.RELEASED);
        payment.setAmount(new BigDecimal("50.00"));
        when(payments.findById(paymentId)).thenReturn(Optional.of(payment));
        bid = new BidEntity();
        bid.setStatus(BidStatus.COMPLETED);
        when(bids.lockForUpdate(bidId)).thenReturn(1);
        when(bids.findById(bidId)).thenReturn(Optional.of(bid));
    }

    @Test
    void aucuneGarde_versementAutorise_colisVerrouilleAvantLePaiement() {
        assertThat(guard.blockingReason(intent)).isNull();
        var order = inOrder(bids, payments);
        order.verify(bids).lockForUpdate(bidId);
        order.verify(payments).findById(paymentId);
    }

    @Test
    void reservationOuPaiementPlusDansLEtatReserve_bloque() {
        intent.setStatus(StripeReleaseIntentEntity.Status.REVERTED);
        assertThat(guard.blockingReason(intent)).contains("réservation REVERTED");
        intent.setStatus(StripeReleaseIntentEntity.Status.PENDING);
        payment.setStatus(PaymentStatus.REFUNDED);
        assertThat(guard.blockingReason(intent)).contains("plus réservé");
    }

    @Test
    void colisAnnule_bloqueMemeAvecDerogation() {
        intent.setOverriddenGuards("DISPUTED,BENEFICIARY_HELD");
        bid.setStatus(BidStatus.CANCELLED);
        assertThat(guard.blockingReason(intent)).isEqualTo("colis CANCELLED");
    }

    @Test
    void remboursementPartiel_bloqueMemeAvecDerogation() {
        intent.setOverriddenGuards("DISPUTED,BENEFICIARY_HELD");
        payment.setRefundedAmount(new BigDecimal("5.00"));
        assertThat(guard.blockingReason(intent)).contains("remboursement partiel");
    }

    @Test
    void litigeAdminOuvert_bloqueMemeAvecDerogation() {
        intent.setOverriddenGuards("DISPUTED,BENEFICIARY_HELD");
        when(disputes.existsByBidIdAndStatusAndTypeStartingWith(eq(bidId), any(), any())).thenReturn(true);
        assertThat(guard.blockingReason(intent)).isEqualTo("litige admin ouvert");
    }

    @Test
    void derogationDuGel_neLevePasLeLitigeBancaire() {
        intent.setOverriddenGuards("BENEFICIARY_HELD");
        payment.setDisputed(true);
        assertThat(guard.blockingReason(intent)).isEqualTo("litige bancaire ouvert");
    }

    @Test
    void derogationDuLitigeBancaire_neLevePasLeGel() {
        intent.setOverriddenGuards("DISPUTED");
        payment.setDisputed(true);
        when(holds.isHeld(travelerId)).thenReturn(true);
        assertThat(guard.blockingReason(intent)).isEqualTo("versements du voyageur gelés");
    }

    @Test
    void derogationDesDeuxGardes_versementAutorise() {
        intent.setOverriddenGuards("DISPUTED,BENEFICIARY_HELD");
        payment.setDisputed(true);
        when(holds.isHeld(travelerId)).thenReturn(true);
        assertThat(guard.blockingReason(intent)).isNull();
    }

    @Test
    void sansDerogation_litigeBancaireEtGelBloquent() {
        payment.setDisputed(true);
        assertThat(guard.blockingReason(intent)).isEqualTo("litige bancaire ouvert");
        payment.setDisputed(false);
        when(holds.isHeld(travelerId)).thenReturn(true);
        assertThat(guard.blockingReason(intent)).isEqualTo("versements du voyageur gelés");
    }

    @Test
    void derogation_neRetientQueLesGardesLevables() {
        assertThat(StripeReleaseExecutor.overriddenGuards(java.util.Set.of())).isNull();
        assertThat(StripeReleaseExecutor.overriddenGuards(null)).isNull();
        assertThat(StripeReleaseExecutor.overriddenGuards(java.util.Set.of("ADMIN_DISPUTE", "CANCELLED"))).isNull();
        assertThat(StripeReleaseExecutor.overriddenGuards(java.util.Set.of("BENEFICIARY_HELD", "DISPUTED", "X")))
                .isEqualTo("DISPUTED,BENEFICIARY_HELD");
        intent.setOverriddenGuards("DISPUTED");
        assertThat(intent.overrides("DISPUTED")).isTrue();
        assertThat(intent.overrides("BENEFICIARY_HELD")).isFalse();
    }
}
