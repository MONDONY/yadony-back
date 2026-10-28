package com.yadony.api.matching.dto;

import java.math.BigDecimal;

/**
 * Résumé, pour le voyageur propriétaire d'un trajet, des colis qu'il a en sa possession
 * (remis en main, en transit ou arrivés, donc scannés et pas encore livrés).
 *
 * @param count     nombre de colis en possession.
 * @param totalKg   kilos transportés (somme des poids déclarés).
 * @param netAmount net estimé que le voyageur recevra, dans la devise du trajet : montant payé
 *                  moins commission des colis réglés en ligne (même formule que « Mon argent »).
 *                  Les colis en espèces n'y comptent pas : le prix se règle de la main à la main.
 * @param currency  devise du trajet.
 */
public record ParcelsSummary(int count, BigDecimal totalKg, BigDecimal netAmount, String currency) {
}
