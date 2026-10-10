package com.yadony.api.common.media;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class PhotoSchedulersTest {

    @Test
    void purgeScheduler_runsPurgeWithScheduleTrigger() {
        PhotoPurgeRunner runner = mock(PhotoPurgeRunner.class);
        new PhotoPurgeScheduler(runner).hourly();
        verify(runner).runPurge(PhotoPurgeRunner.TRIGGER_SCHEDULE);
        verifyNoMoreInteractions(runner);
    }

    @Test
    void reconciliationScheduler_neverAllowsBulk() {
        PhotoReconciliationService service = mock(PhotoReconciliationService.class);
        new PhotoReconciliationScheduler(service).daily();
        verify(service).reconcile(PhotoPurgeRunner.TRIGGER_SCHEDULE);
        verifyNoMoreInteractions(service);
    }
}
