package com.yadony.api.travelerstatus;

/**
 * Statut légal sous lequel un voyageur transporte des colis : particulier ou professionnel.
 * Absent (null) tant que le voyageur ne l'a pas déclaré.
 */
public enum TravelerStatus {
    INDIVIDUAL,
    PROFESSIONAL
}
