package com.yadony.api.matching.dto;

import com.yadony.api.matching.TripSearchPage;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Réponse de {@code GET /announcements} : les champs de {@code PageResponse}, plus
 * {@code bookableElements} quand la recherche inclut les trajets complets (FLUTTER-JH),
 * plus {@code similarAnnouncements} (bloc 2 du classement) quand il y en a.
 *
 * <p>{@code totalElements} compte alors réservables ET complets (la pagination va jusqu'au
 * bout de la liste), {@code bookableElements} les seuls réservables (le compteur). Absent
 * (null, omis du JSON) sans {@code includeFull} : la réponse reste identique à l'ancienne.
 *
 * <p>{@code similarAnnouncements} : annonces du même trajet qui ratent au moins un critère
 * souple, chacune avec la liste de ses écarts. Seulement sur la première page, avec au moins
 * une ville, une taille de page supérieure à 1 et quand le bloc 1 compte peu d'annonces
 * réservables ; absent (null, omis du JSON) sinon. L'appel compteur ({@code size <= 1}) ne le
 * reçoit donc jamais : il garde le chemin SQL simple (aucun score) et la même réponse qu'avant.
 */
public record TripSearchPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last,
        Long bookableElements,
        List<SimilarAnnouncementResponse> similarAnnouncements
) {
    public static <T> TripSearchPageResponse<T> from(Page<T> page) {
        Long bookable = null;
        List<SimilarAnnouncementResponse> similar = null;
        if (page instanceof TripSearchPage<?> trips) {
            bookable = trips.bookableElements();
            similar = trips.similarAnnouncements().isEmpty() ? null : trips.similarAnnouncements();
        }
        return new TripSearchPageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast(),
                bookable,
                similar);
    }
}
