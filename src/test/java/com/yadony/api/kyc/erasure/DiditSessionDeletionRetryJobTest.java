package com.yadony.api.kyc.erasure;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiditSessionDeletionRetryJobTest {

    private final DiditSessionErasureService erasureService = mock(DiditSessionErasureService.class);

    @Test
    void actif_reprendLesLignesDues() {
        when(erasureService.attemptDue()).thenReturn(2);

        new DiditSessionDeletionRetryJob(erasureService, true).scheduledRun();

        verify(erasureService).attemptDue();
    }

    @Test
    void actif_rienADire_quandAucuneLigne() {
        when(erasureService.attemptDue()).thenReturn(0);

        new DiditSessionDeletionRetryJob(erasureService, true).scheduledRun();

        verify(erasureService).attemptDue();
    }

    @Test
    void desactive_neFaitRien() {
        new DiditSessionDeletionRetryJob(erasureService, false).scheduledRun();

        verify(erasureService, never()).attemptDue();
    }
}
