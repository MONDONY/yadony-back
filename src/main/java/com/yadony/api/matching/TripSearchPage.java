package com.yadony.api.matching;

import com.yadony.api.matching.dto.SimilarAnnouncementResponse;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Page de recherche de trajets : le bloc 1 (annonces qui respectent la recherche, paginé
 * normalement) et, sur la première page seulement, le bloc 2 des annonces du même trajet
 * qui ratent au moins un critère souple ({@link #similarAnnouncements()}, non paginé).
 *
 * <p>Avec {@code includeFull} (FLUTTER-JH), le bloc 1 mêle trajets réservables et trajets
 * complets : {@link #getTotalElements()} compte les deux, pour que la pagination aille
 * jusqu'au dernier trajet complet ; {@link #bookableElements()} ne compte que les
 * réservables : c'est lui qu'affiche le compteur « N trajets ». Un trajet complet ne se
 * réserve pas, l'annoncer dans le nombre ferait promettre à l'accueil une offre qui
 * n'existe pas. Sans {@code includeFull}, {@link #bookableElements()} vaut {@code null}.
 */
public class TripSearchPage<T> extends PageImpl<T> {

    private final Long bookableElements;
    private final List<SimilarAnnouncementResponse> similarAnnouncements;

    public TripSearchPage(List<T> content, Pageable pageable, long total, Long bookableElements,
                          List<SimilarAnnouncementResponse> similarAnnouncements) {
        super(content, pageable, total);
        this.bookableElements = bookableElements;
        this.similarAnnouncements = similarAnnouncements == null ? List.of() : List.copyOf(similarAnnouncements);
    }

    /** Trajets réservables du bloc 1 ; {@code null} quand la recherche n'inclut pas les complets. */
    public Long bookableElements() {
        return bookableElements;
    }

    /** Bloc 2 « Autres annonces sur ce trajet », jamais null (vide si non applicable). */
    public List<SimilarAnnouncementResponse> similarAnnouncements() {
        return similarAnnouncements;
    }
}
