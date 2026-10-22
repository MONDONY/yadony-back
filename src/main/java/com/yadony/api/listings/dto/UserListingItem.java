package com.yadony.api.listings.dto;

import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.requests.dto.PackageRequestSearchResponse;
import java.time.LocalDateTime;

/**
 * Une annonce ouverte d'un utilisateur, trajet ou demande d'envoi, dans le format de la
 * recherche : l'app la rend avec les mêmes cartes que l'onglet Recherche.
 *
 * <p>Exactement un de {@code trip} / {@code request} est renseigné, selon {@code kind}
 * ({@code TRIP} ou {@code REQUEST}).
 */
public record UserListingItem(
        String kind,
        /** Date de publication, clé de tri de la liste (la plus récente d'abord). */
        LocalDateTime publishedAt,
        AnnouncementSearchResponse trip,
        PackageRequestSearchResponse request
) {
    public static final String KIND_TRIP = "TRIP";
    public static final String KIND_REQUEST = "REQUEST";
}
