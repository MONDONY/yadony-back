package com.yadony.api.assistant.adminhelp.dto;

/** Bouton vers une page de la console ; {@code path} peut porter une requête ({@code /colis?view=ACTIVE}). */
public record AdminHelpRoute(String path, String label) {
}
