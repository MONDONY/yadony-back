package com.yadony.api.payments.pawapay;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Cycle de vie d'une opération. {@code CREATED} et {@code SUBMIT_REJECTED} sont à nous
 * (avant / au refus de l'initiation) ; les autres viennent de pawaPay. Les trois états
 * finaux sont terminaux : {@code PawapayOperationRepository#applyTransition} ne repart
 * jamais de l'un d'eux.
 *
 * <p>Deux partitions seulement sont déclarées ({@link #FINAL}, {@link #DEAD}) ; les deux
 * autres en sont les compléments, calculés — ajouter un statut ne demande de trancher que
 * ces deux questions : est-il terminal ? est-il mort (n'empêche pas une nouvelle opération) ?
 * Le {@code NOT IN} de {@code applyTransition} et le {@code CHECK} de la migration V241
 * énumèrent, eux, les finaux à la main : à aligner si {@link #FINAL} change.
 */
public enum PawapayOperationStatus {
    CREATED, ACCEPTED, PROCESSING, ENQUEUED, IN_RECONCILIATION, COMPLETED, FAILED, SUBMIT_REJECTED;

    /** Terminaux : plus aucune transition n'en repart. */
    public static final Set<PawapayOperationStatus> FINAL =
            Collections.unmodifiableSet(EnumSet.of(COMPLETED, FAILED, SUBMIT_REJECTED));

    /** Morts : n'empêchent pas une nouvelle opération du même type pour le même paiement. */
    public static final Set<PawapayOperationStatus> DEAD =
            Collections.unmodifiableSet(EnumSet.of(FAILED, SUBMIT_REJECTED));

    /** Non finaux : à surveiller par le poller. */
    public static final Set<PawapayOperationStatus> OPEN =
            Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.copyOf(FINAL)));

    /** Vivantes ou abouties : au plus une par (paiement, type) — index unique partiel V241. */
    public static final Set<PawapayOperationStatus> LIVE_OR_DONE =
            Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.copyOf(DEAD)));

    public boolean isFinal() {
        return FINAL.contains(this);
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
