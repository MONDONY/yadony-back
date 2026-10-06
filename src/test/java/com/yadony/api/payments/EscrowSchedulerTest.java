package com.yadony.api.payments;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EscrowSchedulerTest {

    @Mock PaymentRepository paymentRepository;
    @Mock AdminAlertRepository adminAlertRepository;
    @InjectMocks EscrowScheduler scheduler;

    private static PaymentEntity payment(UUID id, UUID bidId) {
        PaymentEntity payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", id);
        payment.setBidId(bidId);
        payment.setAmount(new BigDecimal("42.00"));
        payment.setStatus(PaymentStatus.ESCROW);
        return payment;
    }

    @Test
    void createsAReadableWarningAlertForAnEscrowOlderThan48h() {
        UUID paymentId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        when(paymentRepository.findByStatusAndCreatedAtBefore(eq(PaymentStatus.ESCROW), any()))
                .thenReturn(List.of(payment(paymentId, bidId)));
        when(adminAlertRepository.findByTypeAndResolved("ESCROW_J48_TIMEOUT", false)).thenReturn(List.of());

        scheduler.checkEscrowTimeouts();

        ArgumentCaptor<AdminAlertEntity> saved = ArgumentCaptor.forClass(AdminAlertEntity.class);
        verify(adminAlertRepository).save(saved.capture());
        AdminAlertEntity alert = saved.getValue();
        assertThat(alert.getType()).isEqualTo("ESCROW_J48_TIMEOUT");
        assertThat(alert.getSeverity()).isEqualTo("WARN");
        assertThat(alert.getDetail()).contains(paymentId.toString()).contains(bidId.toString()).contains("48 h");
        assertThat(alert.getPayload()).contains(paymentId.toString()).contains("42.00");
    }

    @Test
    void skipsAPaymentThatAlreadyHasAnOpenAlert() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findByStatusAndCreatedAtBefore(eq(PaymentStatus.ESCROW), any()))
                .thenReturn(List.of(payment(paymentId, UUID.randomUUID())));
        AdminAlertEntity existing = new AdminAlertEntity();
        existing.setPayload("{\"paymentId\": \"" + paymentId + "\"}");
        when(adminAlertRepository.findByTypeAndResolved("ESCROW_J48_TIMEOUT", false)).thenReturn(List.of(existing));

        scheduler.checkEscrowTimeouts();

        verify(adminAlertRepository, never()).save(any());
    }
}
