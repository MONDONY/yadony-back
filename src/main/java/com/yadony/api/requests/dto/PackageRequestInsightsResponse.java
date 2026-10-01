package com.yadony.api.requests.dto;

import java.util.List;
import java.util.UUID;

/**
 * Chiffres réservés au propriétaire de la demande.
 *
 * @param viewCount         ouvertures du détail (une personne qui rouvre compte à chaque fois).
 *                          Conservé pour les versions de l'app qui l'affichent encore.
 * @param uniqueViewerCount personnes distinctes qui ont ouvert la demande
 */
public record PackageRequestInsightsResponse(long viewCount, long uniqueViewerCount,
                                             List<UUID> invitedAnnouncementIds) {}
