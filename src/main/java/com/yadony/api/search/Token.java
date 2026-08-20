package com.yadony.api.search;

/**
 * Un mot de la requête. {@code normalized} est la forme minuscule sans accent,
 * utilisée pour toutes les comparaisons ; {@code start} et {@code end} pointent
 * dans le texte d'origine pour que le client puisse surligner ce qui a été compris.
 */
public record Token(String raw, String normalized, int start, int end) {}
