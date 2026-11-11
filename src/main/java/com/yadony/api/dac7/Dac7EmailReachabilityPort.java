package com.yadony.api.dac7;

import java.util.UUID;

/**
 * Le voyageur peut-il recevoir un e-mail (adresse connue) ? Réalisé par le paquet {@code notifications}
 * ({@code Dac7EmailReachabilityAdapter}), même motif de port que {@link Dac7VerifiedIdentityPort}. Le récapitulatif
 * DAC7 ne porte ses montants que par e-mail : sans adresse, il n'est ni envoyé ni compté comme envoyé.
 */
public interface Dac7EmailReachabilityPort {

    /** Faux aussi quand l'adresse ne peut pas être lue (le récapitulatif pourra être envoyé plus tard). */
    boolean hasEmail(UUID userId);
}
