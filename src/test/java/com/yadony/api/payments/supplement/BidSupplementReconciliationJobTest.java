package com.yadony.api.payments.supplement;

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
    private final BidSupplementReconciliationJob job = new BidSupplementReconciliationJob(repository, service,
            new BidSupplementWindow(properties, Clock.fixed(NOW, ZoneOffset.UTC)), properties);

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
    void annuleLesDemandesDesColisTerminesDepuis15Minutes() {
        UUID bidId = UUID.randomUUID();
        when(repository.findBidIdsEndedWithSupplementIn(eq(BidSupplementStatus.OPEN),
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
}
