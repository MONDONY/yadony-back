package com.yadony.api.payments.cash.job;

import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.BidCancelledCommissionRefundListener;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionRefundRecoveryJobTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    @Mock private BidRepository bidRepository;
    @Mock private BidCancelledCommissionRefundListener refunder;
    @Mock private AdminAlertService adminAlertService;

    private CommissionRefundRecoveryJob job;

    @BeforeEach
    void setUp() {
        job = new CommissionRefundRecoveryJob(bidRepository, refunder, adminAlertService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void cherche_lesColisClosSansLivraison_entreUneHeureEtSeptJours() {
        when(bidRepository.findChargedCommissionOnClosedBids(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        job.recoverMissedRefunds();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<BidStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<LocalDateTime> before = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> after = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(bidRepository).findChargedCommissionOnClosedBids(eq(PaymentMethod.CASH), eq(CommissionStatus.CHARGED),
                statuses.capture(), before.capture(), after.capture(), page.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                BidStatus.CANCELLED, BidStatus.REJECTED, BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED);
        assertThat(before.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 28, 11, 0));
        assertThat(after.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 21, 12, 0));
        assertThat(page.getValue().getPageSize()).isEqualTo(CommissionRefundRecoveryJob.BATCH);
        verifyNoInteractions(refunder, adminAlertService);
    }

    @Test
    void rejoueChaqueColis_continueApresUnEchec_etAlerteSurCeuxRattrapes() {
        UUID failing = UUID.randomUUID();
        UUID recovered = UUID.randomUUID();
        UUID alreadyRefunded = UUID.randomUUID();
        when(bidRepository.findChargedCommissionOnClosedBids(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(failing, recovered, alreadyRefunded));
        when(refunder.recover(failing)).thenThrow(new IllegalStateException("verrou"));
        when(refunder.recover(recovered)).thenReturn(true);
        when(refunder.recover(alreadyRefunded)).thenReturn(false);

        job.recoverMissedRefunds();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(adminAlertService).raise(eq("commission-refund-recovered"), anyString(), context.capture());
        assertThat(context.getValue()).containsEntry("count", 1).containsEntry("bidIds", recovered.toString());
    }

    @Test
    void rienARattraper_aucuneAlerte() {
        UUID bid = UUID.randomUUID();
        when(bidRepository.findChargedCommissionOnClosedBids(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(bid));
        when(refunder.recover(bid)).thenReturn(false);

        job.recoverMissedRefunds();

        verifyNoInteractions(adminAlertService);
    }

    /** Chaque colis dans sa transaction : une transaction englobante annulerait tout le lot. */
    @Test
    void planifie_etSansTransactionEnglobante() throws Exception {
        var method = CommissionRefundRecoveryJob.class.getMethod("recoverMissedRefunds");
        assertThat(method.isAnnotationPresent(Scheduled.class)).isTrue();
        assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
    }
}
