package com.yadony.api.matching.dto;

import com.yadony.api.matching.TripSearchPage;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Réponse de {@code GET /announcements} : les champs de {@code PageResponse}, plus
 * {@code bookableElements} quand la recherche inclut les trajets complets (FLUTTER-JH).
 *
 * <p>{@code totalElements} compte alors réservables ET complets (la pagination va jusqu'au
 * bout de la liste), {@code bookableElements} les seuls réservables (le compteur). Absent
 * (null, omis du JSON) sans {@code includeFull} : la réponse reste identique à l'ancienne.
 */
public record TripSearchPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last,
        Long bookableElements
) {
    public static <T> TripSearchPageResponse<T> from(Page<T> page) {
        Long bookable = page instanceof TripSearchPage<?> withFull ? withFull.bookableElements() : null;
        return new TripSearchPageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast(),
                bookable);
    }
}
