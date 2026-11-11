package com.yadony.api.dac7;

import java.time.LocalDate;

/**
 * Identité lue sur la pièce vérifiée (voir {@link Dac7VerifiedIdentityPort}). {@link #toString()} ne montre aucune
 * valeur : ces données ne vont jamais dans un journal.
 */
public record Dac7VerifiedIdentity(String firstName, String lastName, LocalDate birthDate, Dac7Address address) {

    @Override
    public String toString() {
        return "Dac7VerifiedIdentity[masqué]";
    }
}
