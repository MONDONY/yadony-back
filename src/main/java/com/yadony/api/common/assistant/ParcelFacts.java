package com.yadony.api.common.assistant;

import java.util.List;
import java.util.Set;

/**
 * Faits sur un colis, en phrases anglaises courtes destinées au modèle : des conclusions
 * (« cancellation is no longer possible ») plutôt que des données brutes, sans code ni donnée
 * personnelle.
 */
public record ParcelFacts(List<String> lines, Set<ParcelLink> links) {

    public static final ParcelFacts NONE = new ParcelFacts(List.of(), Set.of());

    public ParcelFacts {
        lines = List.copyOf(lines);
        links = Set.copyOf(links);
    }

    public static ParcelFacts of(List<String> lines) {
        return new ParcelFacts(lines, Set.of());
    }
}
