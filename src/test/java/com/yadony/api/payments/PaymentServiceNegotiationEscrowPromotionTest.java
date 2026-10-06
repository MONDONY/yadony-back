package com.yadony.api.payments;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Le paiement carte d'un fil de négociation passe en séquestre dès que le /checkout synchrone a vu
 * Stripe confirmer l'autorisation. Avant, seul le webhook amount_capturable_updated le faisait, et
 * staging ne l'a jamais reçu : 6 paiements PENDING, dont 3 livrés sans être capturés (INV-08).
 */
class PaymentServiceNegotiationEscrowPromotionTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PaymentService service = PaymentServiceTestFactory.bare(
            paymentRepository, mock(UserRepository.class), auditService, mock(AdminAlertService.class));

    private final UUID THREAD = UUID.randomUUID();

    private PaymentEntity payment(PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setNegotiationThreadId(THREAD);
        p.setStripePaymentIntentId("pi_nego");
        p.setStatus(status);
        PaymentServiceTestFactory.setId(p, UUID.randomUUID());
        return p;
    }

    private PaymentEntity locked(PaymentStatus status) {
        PaymentEntity p = payment(status);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(THREAD)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    @DisplayName("PENDING → ESCROW, identifiant de charge posé, audit PAYMENT_ESCROW_ACTIVE, true")
    void pending_isPromotedToEscrow() {
        PaymentEntity p = locked(PaymentStatus.PENDING);

        assertThat(service.promoteNegotiationEscrowIfPending(THREAD, "pi_nego", "ch_1")).isTrue();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(p.getStripeChargeId()).isEqualTo("ch_1");
        verify(paymentRepository).save(p);
        verify(auditService).log(eq("PAYMENT"), eq(p.getId()), eq("PAYMENT_ESCROW_ACTIVE"), eq(THREAD), any());
    }

    @Test
    @DisplayName("déjà ESCROW (webhook arrivé avant) : rien ne change, ni audit ni sauvegarde, false")
    void alreadyEscrow_isIdempotent() {
        PaymentEntity p = locked(PaymentStatus.ESCROW);
        p.setStripeChargeId("ch_1");

        assertThat(service.promoteNegotiationEscrowIfPending(THREAD, "pi_nego", "ch_1")).isFalse();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("déjà ESCROW mais charge inconnue : la charge est complétée, sans audit ni nouvelle promotion")
    void alreadyEscrow_missingChargeIsFilled() {
        PaymentEntity p = locked(PaymentStatus.ESCROW);

        assertThat(service.promoteNegotiationEscrowIfPending(THREAD, "pi_nego", "ch_late")).isFalse();

        assertThat(p.getStripeChargeId()).isEqualTo("ch_late");
        verify(paymentRepository).save(p);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("la charge déjà connue n'est jamais écrasée")
    void existingChargeIsKept() {
        PaymentEntity p = locked(PaymentStatus.PENDING);
        p.setStripeChargeId("ch_first");

        service.promoteNegotiationEscrowIfPending(THREAD, "pi_nego", "ch_second");

        assertThat(p.getStripeChargeId()).isEqualTo("ch_first");
    }

    @Test
    @DisplayName("charge absente (Stripe ne l'a pas encore) : promu quand même, sans charge")
    void nullCharge_stillPromotes() {
        PaymentEntity p = locked(PaymentStatus.PENDING);

        assertThat(service.promoteNegotiationEscrowIfPending(THREAD, "pi_nego", null)).isTrue();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(p.getStripeChargeId()).isNull();
    }

    @Test
    @DisplayName("un paiement annulé, remboursé ou versé n'est jamais ressuscité")
    void terminalStatuses_areNeverResurrected() {
        for (PaymentStatus terminal : new PaymentStatus[]{
                PaymentStatus.CANCELLED, PaymentStatus.REFUNDED, PaymentStatus.RELEASED}) {
            PaymentRepository repo = mock(PaymentRepository.class);
            AuditService audit = mock(AuditService.class);
            PaymentService svc = PaymentServiceTestFactory.bare(
                    repo, mock(UserRepository.class), audit, mock(AdminAlertService.class));
            PaymentEntity p = payment(terminal);
            when(repo.findByNegotiationThreadIdForUpdate(THREAD)).thenReturn(Optional.of(p));

            assertThat(svc.promoteNegotiationEscrowIfPending(THREAD, "pi_nego", null)).isFalse();

            assertThat(p.getStatus()).isEqualTo(terminal);
            verifyNoInteractions(audit);
        }
    }

    @Test
    @DisplayName("PaymentIntent différent de celui du fil : refusé, rien ne bouge")
    void foreignPaymentIntent_isRejected() {
        PaymentEntity p = locked(PaymentStatus.PENDING);

        assertThat(service.promoteNegotiationEscrowIfPending(THREAD, "pi_other", "ch_x")).isFalse();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("aucun paiement pour le fil, ou PaymentIntent nul : false")
    void noPaymentOrNullPi_returnsFalse() {
        when(paymentRepository.findByNegotiationThreadIdForUpdate(THREAD)).thenReturn(Optional.empty());
        assertThat(service.promoteNegotiationEscrowIfPending(THREAD, "pi_nego", "ch")).isFalse();

        locked(PaymentStatus.PENDING);
        assertThat(service.promoteNegotiationEscrowIfPending(THREAD, null, "ch")).isFalse();
    }
}
