package com.yadony.api.payments.supplement;

import com.yadony.api.tracking.HandoverBlocker;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Tant qu'un supplément attend une réponse non échue ou un paiement, la remise (scan DEPART) est
 * refusée en 409 {@code handover-supplement-pending}. Une demande échue que le filet n'a pas encore
 * fermée ne bloque plus (l'expéditeur ne peut déjà plus y répondre) : la porte l'expire elle-même,
 * sous le verrou du colis que tient le scan. Même chose pour une commission espèces restée en 3DS
 * au-delà de {@code commission-confirm-window} (30 min) : la porte l'annule comme le filet, sans
 * attendre son passage (jusqu'à 10 min). Un paiement en vol ({@code PAYMENT_PENDING}) bloque
 * jusqu'à son issue.
 */
@Component
public class BidSupplementHandoverGate implements HandoverBlocker {

    public static final String CODE = "handover-supplement-pending";

    private final BidSupplementRepository supplements;
    private final BidSupplementService service;
    private final BidSupplementWindow window;

    public BidSupplementHandoverGate(BidSupplementRepository supplements, BidSupplementService service,
                                     BidSupplementWindow window) {
        this.supplements = supplements;
        this.service = service;
        this.window = window;
    }

    @Override
    public Optional<String> blockingCode(UUID bidId) {
        Optional<BidSupplementEntity> open = supplements.findByBidIdAndStatusInForUpdate(bidId, BidSupplementStatus.OPEN);
        if (open.isEmpty()) {
            return Optional.empty();
        }
        BidSupplementEntity s = open.get();
        if (BidSupplementViews.blocksHandover(s, window.now(), window.commissionConfirmWindow())) {
            return Optional.of(CODE);
        }
        service.expireUnderBidLock(s);
        return Optional.empty();
    }
}
