package com.yadony.api.payments.amendment;

import com.yadony.api.tracking.HandoverBlocker;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Tant qu'une modification du colis est ouverte et que son étape en cours n'est pas échue
 * ({@link BidAmendmentWindow#isLapsed}), la remise (scan DEPART) est refusée en 409 {@value #CODE} :
 * <ul>
 *   <li>{@code AUTHORIZATION_PENDING} jusqu'à la fin de {@code authorization-window} (3DS de la carte) ;</li>
 *   <li>{@code PROPOSED} jusqu'à {@code expiresAt} (réponse du voyageur) ;</li>
 *   <li>{@code COMMISSION_PENDING} jusqu'à la fin de {@code commission-confirm-window} (3DS de la commission) ;</li>
 *   <li>{@code PAYMENT_PENDING} jusqu'à {@code paymentExpiresAt} (dépôt de l'expéditeur).</li>
 * </ul>
 * Une étape échue que le filet n'a pas encore fermée ne bloque plus : le portail la clôt lui-même, sous le verrou du
 * colis que tient le scan (point de vigilance n° 5), sans attendre le filet. Un dépôt mobile money en vol bloque
 * jusqu'à son issue (il ne s'annule pas).
 *
 * <p>Verrous : colis (le scan), puis trajet et modification (pris par {@link BidAmendmentService#expireForHandover}),
 * le même ordre que le service. La lecture sans verrou de la modification est cohérente : toute écriture sur une
 * modification prend d'abord le verrou du colis.
 */
@Component
public class BidAmendmentHandoverGate implements HandoverBlocker {

    public static final String CODE = "handover-amendment-pending";

    private final BidAmendmentRepository amendments;
    private final BidAmendmentService service;
    private final BidAmendmentWindow window;

    public BidAmendmentHandoverGate(BidAmendmentRepository amendments, BidAmendmentService service,
                                    BidAmendmentWindow window) {
        this.amendments = amendments;
        this.service = service;
        this.window = window;
    }

    @Override
    public Optional<String> blockingCode(UUID bidId) {
        Optional<BidAmendmentEntity> open = amendments.findFirstByBidIdAndStatusIn(bidId, BidAmendmentStatus.OPEN);
        if (open.isEmpty()) {
            return Optional.empty();
        }
        BidAmendmentEntity a = open.get();
        if (!window.isLapsed(a)) {
            return Optional.of(CODE);
        }
        return service.expireForHandover(bidId, a.getId()) ? Optional.empty() : Optional.of(CODE);
    }
}
