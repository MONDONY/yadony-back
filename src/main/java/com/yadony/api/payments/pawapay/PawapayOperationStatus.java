package com.yadony.api.payments.pawapay;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Cycle de vie d'une opération. {@code CREATED} et {@code SUBMIT_REJECTED} sont à nous
 * (avant / au refus de l'initiation) ; les autres viennent de pawaPay. Les trois états
 * finaux sont terminaux : {@code PawapayOperationRepository#applyTransition} ne repart
 * jamais de l'un d'eux.
 */
public enum PawapayOperationStatus {
    CREATED, ACCEPTED, PROCESSING, ENQUEUED, IN_RECONCILIATION, COMPLETED, FAILED, SUBMIT_REJECTED;

    /** Non finaux : à surveiller par le poller. */
    public static final Set<PawapayOperationStatus> OPEN =
            EnumSet.of(CREATED, ACCEPTED, PROCESSING, ENQUEUED, IN_RECONCILIATION);

    /** Morts : n'empêchent pas une nouvelle opération du même type pour le même paiement. */
    public static final Set<PawapayOperationStatus> DEAD = EnumSet.of(FAILED, SUBMIT_REJECTED);

    /** Vivantes ou abouties : au plus une par (paiement, type) — index unique partiel V241. */
    public static final Set<PawapayOperationStatus> LIVE_OR_DONE =
            EnumSet.of(CREATED, ACCEPTED, PROCESSING, ENQUEUED, IN_RECONCILIATION, COMPLETED);

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == SUBMIT_REJECTED;
    }

    /** Statut tel qu'écrit par pawaPay ; vide si inconnu (on l'ignore, on ne casse pas). */
    public static Optional<PawapayOperationStatus> fromApi(String raw) {
        if (raw == null) return Optional.empty();
        try {
            PawapayOperationStatus s = valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return s == CREATED || s == SUBMIT_REJECTED ? Optional.empty() : Optional.of(s);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
