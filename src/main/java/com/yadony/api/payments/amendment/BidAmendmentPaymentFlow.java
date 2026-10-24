package com.yadony.api.payments.amendment;

import com.yadony.api.common.TransientLockRetry;
import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.payments.PaymentBusy;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.amendment.dto.AmendmentCreateResponse;
import com.yadony.api.payments.amendment.dto.CreateAmendmentRequest;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Création, acceptation et confirmation de commission d'une modification, sans transaction englobante, comme
 * {@code BidSupplementPaymentFlow}.
 *
 * <p>Deux temps : la transaction du service (verrous du colis, du trajet puis de la modification, contrôles, état du
 * rail), puis, APRÈS son commit et hors transaction, la suite du rail ({@link BidAmendmentRailHandler#afterCreateCommit},
 * {@link BidAmendmentRailHandler#afterAcceptCommit}) : jamais d'appel prestataire sous un verrou. Un conflit de
 * verrou (interblocage avec l'ordre trajet → colis de {@code TripRescheduleService} ou {@code CancellationService},
 * {@code CannotAcquireLockException}) est rejoué dans une transaction neuve, puis rendu en 409 {@code payment-busy}.
 */
@Component
public class BidAmendmentPaymentFlow {

    private final BidAmendmentService service;
    private final BidAmendmentCashRail cashRail;
    private final BidAmendmentProperties properties;
    private final ObjectProvider<BidAmendmentRailHandler> handlers;

    public BidAmendmentPaymentFlow(BidAmendmentService service, BidAmendmentCashRail cashRail,
                                   BidAmendmentProperties properties, ObjectProvider<BidAmendmentRailHandler> handlers) {
        this.service = service;
        this.cashRail = cashRail;
        this.properties = properties;
        this.handlers = handlers;
    }

    public AmendmentCreateResponse create(UUID bidId, String firebaseUid, CreateAmendmentRequest request) {
        AmendmentCreateResponse created = TransientLockRetry.run("amendment create " + bidId,
                () -> service.create(bidId, firebaseUid, request), conflict -> PaymentBusy.exception());
        return handler(created.amendment()).map(h -> h.afterCreateCommit(created)).orElse(created);
    }

    public AmendmentAcceptResponse accept(UUID bidId, UUID amendmentId, String firebaseUid,
                                          AmendmentAcceptRequest request) {
        AmendmentAcceptResponse prepared = TransientLockRetry.run("amendment accept " + amendmentId,
                () -> service.accept(bidId, amendmentId, firebaseUid, request), conflict -> PaymentBusy.exception());
        return handler(prepared.amendment()).map(h -> h.afterAcceptCommit(prepared)).orElse(prepared);
    }

    /**
     * Rail espèces, après la 3DS de la carte commission : voir {@link BidAmendmentCashRail#confirmCommission}. Coupé
     * drapeau éteint, comme l'acceptation qu'il termine.
     */
    public BidAmendmentResponse confirmCommission(UUID bidId, UUID amendmentId, String firebaseUid) {
        if (!properties.isEnabled()) {
            throw BidAmendmentEligibility.exceptionFor("amendment-disabled");
        }
        return TransientLockRetry.run("amendment commission confirm " + amendmentId,
                () -> cashRail.confirmCommission(bidId, amendmentId, firebaseUid), conflict -> PaymentBusy.exception());
    }

    /** Composant du rail de la modification, s'il est livré. */
    private Optional<BidAmendmentRailHandler> handler(BidAmendmentResponse amendment) {
        if (amendment == null || amendment.rail() == null) {
            return Optional.empty();
        }
        BidSupplementRail rail = BidSupplementRail.valueOf(amendment.rail());
        return handlers.orderedStream().filter(h -> h.rail() == rail).findFirst();
    }
}
