package com.yadony.api.matching.dto;

/**
 * Audience d'un trajet, réservée à son voyageur.
 *
 * @param uniqueViewerCount personnes connectées, autres que le voyageur, qui ont ouvert
 *                          le trajet dans l'app pendant qu'il était en ligne (une fois chacune)
 * @param shareViewCount    consultations de la page web publique de l'affiche partagée
 */
public record AnnouncementInsightsResponse(long uniqueViewerCount, long shareViewCount) {}
