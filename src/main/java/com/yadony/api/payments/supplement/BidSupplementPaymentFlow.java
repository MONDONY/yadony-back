package com.yadony.api.payments.supplement;

import com.yadony.api.common.TransientLockRetry;
import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.PaymentBusy;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paiement d'un supplément par l'expéditeur, sans transaction englobante.
 *
 * <p>Acceptation en deux temps : la transaction de {@link BidSupplementService#accept} (verrous,
 * contrôles, passage à l'état du rail), puis, APRÈS son commit et hors transaction,
 * {@link BidSupplementRailHandler#afterAcceptCommit} (création du PaymentIntent pour la carte).
 * Jamais d'appel prestataire sous le verrou du colis. Un interblocage est rejoué dans une
 * transaction neuve, puis rendu en 409 {@code payment-busy} (comme {@code POST /bids/{id}/confirm-payment}).
 */
@Component
public class BidSupplementPaymentFlow {

    private final BidSupplementService service;
    private final BidSupplementCardRail cardRail;
    private final BidSupplementCashRail cashRail;
    private final Map<BidSupplementRail, BidSupplementRailHandler> handlers = new EnumMap<>(BidSupplementRail.class);

    public BidSupplementPaymentFlow(BidSupplementService service, BidSupplementCardRail cardRail,
                                    BidSupplementCashRail cashRail, List<BidSupplementRailHandler> handlers) {
        this.service = service;
        this.cardRail = cardRail;
        this.cashRail = cashRail;
        handlers.forEach(h -> {
            if (h.rail() != null) {
                this.handlers.putIfAbsent(h.rail(), h);
            }
        });
    }

    /**
     * Création en deux temps, comme l'acceptation : la transaction de
     * {@link BidSupplementService#create} (verrou du colis, commission prélevée sur le solde pour
     * les espèces), puis, APRÈS son commit, {@link BidSupplementRailHandler#afterCreateCommit}
     * (débit de la carte commission, rail espèces).
     */
    public BidSupplementResponse create(UUID bidId, String firebaseUid, CreateSupplementRequest request) {
        BidSupplementResponse created = TransientLockRetry.run("supplement create " + bidId,
                () -> service.create(bidId, firebaseUid, request), conflict -> PaymentBusy.exception());
        BidSupplementRailHandler handler = created.rail() == null ? null
                : handlers.get(BidSupplementRail.valueOf(created.rail()));
        return handler == null ? created : handler.afterCreateCommit(created);
    }

    /** Rail espèces, après la 3DS du voyageur : voir {@link BidSupplementCashRail#confirmCommission}. */
    public BidSupplementResponse confirmCommission(UUID bidId, UUID supplementId, String firebaseUid) {
        return TransientLockRetry.run("supplement commission confirm " + supplementId,
                () -> cashRail.confirmCommission(bidId, supplementId, firebaseUid), conflict -> PaymentBusy.exception());
    }

    public SupplementAcceptResponse accept(UUID bidId, UUID supplementId, String firebaseUid) {
        SupplementAcceptResponse prepared = TransientLockRetry.run("supplement accept " + supplementId,
                () -> service.accept(bidId, supplementId, firebaseUid), conflict -> PaymentBusy.exception());
        BidSupplementRailHandler handler = prepared.rail() == null ? null
                : handlers.get(BidSupplementRail.valueOf(prepared.rail()));
        return handler == null ? prepared : handler.afterAcceptCommit(prepared);
    }

    /** Filet carte de l'app après la PaymentSheet : voir {@link BidSupplementCardRail#confirmPayment}. */
    public BidSupplementResponse confirmPayment(UUID bidId, UUID supplementId, String firebaseUid) {
        return TransientLockRetry.run("supplement confirm-payment " + supplementId,
                () -> cardRail.confirmPayment(bidId, supplementId, firebaseUid), conflict -> PaymentBusy.exception());
    }
}
