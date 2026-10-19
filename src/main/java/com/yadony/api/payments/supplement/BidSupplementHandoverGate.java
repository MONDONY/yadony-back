package com.yadony.api.payments.supplement;

import com.yadony.api.tracking.HandoverBlocker;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Tant qu'un supplément attend une réponse ou un paiement, la remise (scan DEPART) est refusée en
 * 409 {@code handover-supplement-pending}. Refusé, expiré, retiré ou payé : la remise reprend.
 */
@Component
public class BidSupplementHandoverGate implements HandoverBlocker {

    public static final String CODE = "handover-supplement-pending";

    private final BidSupplementRepository supplements;

    public BidSupplementHandoverGate(BidSupplementRepository supplements) {
        this.supplements = supplements;
    }

    @Override
    public Optional<String> blockingCode(UUID bidId) {
        return supplements.existsByBidIdAndStatusIn(bidId, BidSupplementStatus.OPEN) ? Optional.of(CODE) : Optional.empty();
    }
}
