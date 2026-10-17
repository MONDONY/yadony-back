package com.yadony.api.gppro;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GpProDocumentCleanupSchedulerTest {

    @Mock GpProDocumentRepository documents;
    @Mock GpProDocumentService documentService;

    GpProDocumentCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new GpProDocumentCleanupScheduler(documents, documentService,
                new GpProProperties(null, null, true));
        when(documents.findOrphansCreatedBefore(any())).thenReturn(List.of());
        when(documents.findUnpurgedOfDecidedApplications()).thenReturn(List.of());
    }

    @Test
    void purgesOrphansOlderThan24h() {
        GpProDocumentEntity orphan = new GpProDocumentEntity();
        when(documents.findOrphansCreatedBefore(any())).thenReturn(List.of(orphan));
        when(documentService.purge(orphan)).thenReturn(true);

        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        scheduler.run();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(documents).findOrphansCreatedBefore(cutoff.capture());
        assertThat(Duration.between(cutoff.getValue(), before).toHours()).isBetween(23L, 24L);
        verify(documentService).purge(orphan);
    }

    @Test
    void keepsRecentOrphans() {
        // Le dépôt, filtré sur le seuil de 24 h, ne remonte rien : aucune purge.
        scheduler.run();
        verify(documentService, never()).purge(any());
    }

    @Test
    void retriesFailedPurgeOfDecidedApplication() {
        GpProDocumentEntity left = new GpProDocumentEntity();
        when(documents.findUnpurgedOfDecidedApplications()).thenReturn(List.of(left));
        when(documentService.purge(left)).thenReturn(false).thenReturn(true);

        scheduler.run();
        scheduler.run();

        verify(documentService, times(2)).purge(left);
    }

    @Test
    void oneFailureDoesNotStopTheOthers() {
        GpProDocumentEntity a = new GpProDocumentEntity();
        GpProDocumentEntity b = new GpProDocumentEntity();
        when(documents.findUnpurgedOfDecidedApplications()).thenReturn(List.of(a, b));
        when(documentService.purge(a)).thenReturn(false);
        when(documentService.purge(b)).thenReturn(true);
        scheduler.run();
        verify(documentService).purge(b);
    }

    @Test
    void disabledSchedulerDoesNothing() {
        new GpProDocumentCleanupScheduler(documents, documentService, new GpProProperties(null, null, false)).run();
        verifyNoInteractions(documents, documentService);
    }
}
