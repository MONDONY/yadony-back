package com.yadony.api.travelerstatus;

import com.yadony.api.travelerstatus.dto.PublishingEligibilityResponse;
import org.mockito.Mockito;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/** Garde de publication dont la décision d'éligibilité est imposée, pour les tests des services appelants. */
public final class PublishingLimitGateStubs {

    private PublishingLimitGateStubs() {
    }

    public static PublishingLimitGate gateReturning(PublishingEligibilityReason reason) {
        PublishingEligibilityService eligibility = Mockito.mock(PublishingEligibilityService.class);
        PublishingEligibilityResponse decision = new PublishingEligibilityResponse(
                reason == PublishingEligibilityReason.OK, reason, reason == PublishingEligibilityReason.OK ? null : 2,
                2, LocalDate.of(2026, 12, 1), null);
        lenient().when(eligibility.evaluateForPublishing(any())).thenReturn(decision);
        lenient().when(eligibility.evaluateForPublishing(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(decision);
        return new PublishingLimitGate(eligibility);
    }
}
