package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BidSupplementReconciliationJobTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final BidSupplementRepository repository = mock(BidSupplementRepository.class);
    private final BidSupplementService service = mock(BidSupplementService.class);
    private final BidSupplementProperties properties = new BidSupplementProperties();
    private final AdminAlertEscalator alerts = mock(AdminAlertEscalator.class);
    private final BidSupplementReconciliationJob job = new BidSupplementReconciliationJob(repository, service,
            new BidSupplementWindow(properties, Clock.fixed(NOW, ZoneOffset.UTC)), properties, alerts);

    @Test
    void expireLesDemandesEchues_unEchecNArretePasLeLot() {
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        UUID alreadyClosed = UUID.randomUUID();
        when(repository.findIdsOpenExpiredBefore(eq(BidSupplementStatus.OPEN), eq(NOW_UTC), any(Pageable.class)))
                .thenReturn(List.of(failing, ok, alreadyClosed));
        when(service.expire(failing)).thenThrow(new IllegalStateException("verrou"));
        when(service.expire(ok)).thenReturn(true);

        assertThat(job.run().expired()).isEqualTo(1);
        verify(service).expire(alreadyClosed);
    }

    @Test
    void annuleLesDemandesDesColisTerminesDepuis15Minutes_payeesComprises() {
        assertThat(BidSupplementReconciliationJob.ENDED_BID_STATUSES).containsAll(BidSupplementStatus.OPEN)
                .contains(BidSupplementStatus.PAID).doesNotContain(BidSupplementStatus.REFUND_PENDING);
        UUID bidId = UUID.randomUUID();
        when(repository.findBidIdsEndedWithSupplementIn(eq(BidSupplementReconciliationJob.ENDED_BID_STATUSES),
                eq(BidSupplementService.TERMINAL_UNDELIVERED), eq(NOW_UTC.minusMinutes(15)), any(Pageable.class)))
                .thenReturn(List.of(bidId));
        when(service.onBidEnded(bidId, "BID_ENDED")).thenReturn(true);

        assertThat(job.run().cancelled()).isEqualTo(1);
    }

    @Test
    void filetCoupe_rienNeTourne() {
        properties.setReconcileEnabled(false);
        job.scheduledRun();
        verifyNoInteractions(repository, service);

        properties.setReconcileEnabled(true);
        job.scheduledRun();
        verify(repository).findIdsOpenExpiredBefore(any(), any(), any());
        verify(service, never()).onBidEnded(any(), any());
    }

    @Test
    void supplementPayeApresUnPartageDeLitige_alerteAJPlus3_uneFoisParSupplement_etUnEchecNArretePasLeLot() {
        UUID failing = UUID.randomUUID();
        UUID refundedMeanwhile = UUID.randomUUID();
        BidSupplementEntity s = paid(UUID.randomUUID());
        when(repository.findIdsUnsettledAfterPrincipalDecision(eq(NOW_UTC.minusDays(3)), any(Pageable.class)))
                .thenReturn(List.of(failing, refundedMeanwhile, s.getId()));
        when(repository.findById(failing)).thenThrow(new IllegalStateException("base"));
        BidSupplementEntity refunded = paid(refundedMeanwhile);
        refunded.setStatus(BidSupplementStatus.REFUND_PENDING);
        when(repository.findById(s.getId())).thenReturn(java.util.Optional.of(s));
        when(alerts.raiseOnce(eq("SUPP_UNSETTLED_" + s.getId()), eq("WARN"), anyString(), anyMap())).thenReturn(true);

        assertThat(job.run().unsettled()).isEqualTo(1);

        verify(alerts).raiseOnce(eq("SUPP_UNSETTLED_" + s.getId()), eq("WARN"),
                argThat(detail -> detail.contains("décision manuelle")),
                argThat(context -> s.getBidId().toString().equals(context.get("bidId"))
                        && "STRIPE".equals(context.get("rail"))
                        && String.valueOf(context.get("actions")).contains("/admin/supplements/" + s.getId())));
        verify(alerts, never()).raiseOnce(eq("SUPP_UNSETTLED_" + refundedMeanwhile), anyString(), anyString(),
                anyMap());
        assertThat(("SUPP_UNSETTLED_" + UUID.randomUUID()).length())
                .isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
    }

    @Test
    void alerteDejaOuverte_nEstPasComptee() {
        BidSupplementEntity s = paid(UUID.randomUUID());
        when(repository.findIdsUnsettledAfterPrincipalDecision(any(), any(Pageable.class))).thenReturn(List.of(s.getId()));
        when(repository.findById(s.getId())).thenReturn(java.util.Optional.of(s));
        when(alerts.raiseOnce(anyString(), anyString(), anyString(), anyMap())).thenReturn(false);

        assertThat(job.run().unsettled()).isZero();
    }

    private BidSupplementEntity paid(UUID id) {
        BidSupplementEntity s = new BidSupplementEntity();
        BidSupplementPricingTest.setId(s, id);
        s.setBidId(UUID.randomUUID());
        s.setRail(BidSupplementRail.STRIPE);
        s.setStatus(BidSupplementStatus.PAID);
        s.setCurrency("EUR");
        s.setNetAmount(new java.math.BigDecimal("20.00"));
        s.setCommissionAmount(new java.math.BigDecimal("1.10"));
        s.setGrossAmount(new java.math.BigDecimal("21.10"));
        s.setPaidAt(NOW_UTC.minusDays(5));
        when(repository.findById(id)).thenReturn(java.util.Optional.of(s));
        return s;
    }
}
