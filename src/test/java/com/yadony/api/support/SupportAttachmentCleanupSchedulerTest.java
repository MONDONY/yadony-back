package com.yadony.api.support;

import com.yadony.api.common.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportAttachmentCleanupSchedulerTest {

    @Mock private StorageService storageService;
    @Mock private SupportMessageAttachmentRepository attachmentRepository;

    @InjectMocks private SupportAttachmentCleanupScheduler scheduler;

    @Test
    void deletesOnlyTheKeysNoMessageReferences() {
        when(storageService.listKeysOlderThan(eq("support/"), any(Instant.class)))
                .thenReturn(List.of("support/u/attached.jpg", "support/u/orphan.jpg"));
        when(attachmentRepository.findObjectKeysByObjectKeyIn(
                List.of("support/u/attached.jpg", "support/u/orphan.jpg")))
                .thenReturn(List.of("support/u/attached.jpg"));

        scheduler.purgeOrphanAttachments();

        verify(storageService).deleteFile("support/u/orphan.jpg");
        verify(storageService, never()).deleteFile("support/u/attached.jpg");
    }

    /** Idempotent : sans candidat, aucun appel de suppression. */
    @Test
    void doesNothingWhenThereIsNoCandidate() {
        when(storageService.listKeysOlderThan(eq("support/"), any(Instant.class)))
                .thenReturn(List.of());

        scheduler.purgeOrphanAttachments();

        verify(storageService, never()).deleteFile(anyString());
    }

    /** Resilience : si une deletion echoue, le sweep continue sur les autres. */
    @Test
    void continuesTheSweepWhenOneDeletionFails() {
        when(storageService.listKeysOlderThan(eq("support/"), any(Instant.class)))
                .thenReturn(List.of("support/u/orphan1.jpg", "support/u/orphan2.jpg"));
        when(attachmentRepository.findObjectKeysByObjectKeyIn(
                List.of("support/u/orphan1.jpg", "support/u/orphan2.jpg")))
                .thenReturn(List.of()); // Both are orphans

        doThrow(new RuntimeException("s3 down")).when(storageService).deleteFile("support/u/orphan1.jpg");

        scheduler.purgeOrphanAttachments();

        verify(storageService).deleteFile("support/u/orphan1.jpg");
        verify(storageService).deleteFile("support/u/orphan2.jpg");
    }
}
