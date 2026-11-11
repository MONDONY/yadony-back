package com.yadony.api.dac7;

import java.time.LocalDate;

/**
 * Identité lue sur la pièce vérifiée (voir {@link Dac7VerifiedIdentityPort}). {@link #toString()} ne montre aucune
 * valeur : ces données ne vont jamais dans un journal.
 *
 * @param verificationRef identifiant de la session de vérification chez le prestataire (Didit), gardé au registre DAC7
 *                        comme preuve de fiabilité quand une valeur en est reprise ; {@code null} s'il est inconnu
 */
public record Dac7VerifiedIdentity(String firstName, String lastName, LocalDate birthDate, Dac7Address address,
                                   String verificationRef) {

    public Dac7VerifiedIdentity(String firstName, String lastName, LocalDate birthDate, Dac7Address address) {
        this(firstName, lastName, birthDate, address, null);
    }

    @Override
    public String toString() {
        return "Dac7VerifiedIdentity[masqué]";
    }
}
