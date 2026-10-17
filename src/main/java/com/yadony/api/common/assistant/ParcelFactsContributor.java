package com.yadony.api.common.assistant;

/**
 * Chaque package qui sait quelque chose d'un colis (suivi, annulation, paiement, litige)
 * l'implémente ; l'assistant les interroge toutes, dans l'ordre de {@code @Order}, sans
 * dépendre de leurs services. Le rôle du {@link ParcelRef} borne ce qui peut être dit.
 */
public interface ParcelFactsContributor {

    ParcelFacts facts(ParcelRef parcel);
}
