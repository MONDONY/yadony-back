package com.yadony.api.videomodal;

/**
 * Corridor visé par une campagne : ville ou pays de départ et d'arrivée, en texte libre. Un côté nul
 * veut dire « n'importe lequel ». La comparaison (insensible à la casse, aux accents et aux espaces)
 * est faite par l'app, qui connaît le corridor concerné par l'événement.
 */
public record VideoModalCorridor(String from, String to) {

    public static final int MAX_ENTRIES = 10;
    public static final int MAX_LENGTH = 80;
}
