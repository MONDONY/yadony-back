package com.yadony.api.matching;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Page de recherche qui mêle trajets réservables et trajets complets (FLUTTER-JH).
 *
 * <p>{@link #getTotalElements()} compte les deux, pour que la pagination aille jusqu'au
 * dernier trajet complet ; {@link #bookableElements()} ne compte que les réservables :
 * c'est lui qu'affiche le compteur « N trajets ». Un trajet complet ne se réserve pas,
 * l'annoncer dans le nombre ferait promettre à l'accueil une offre qui n'existe pas.
 */
public class TripSearchPage<T> extends PageImpl<T> {

    private final long bookableElements;

    public TripSearchPage(List<T> content, Pageable pageable, long total, long bookableElements) {
        super(content, pageable, total);
        this.bookableElements = bookableElements;
    }

    public long bookableElements() {
        return bookableElements;
    }
}
