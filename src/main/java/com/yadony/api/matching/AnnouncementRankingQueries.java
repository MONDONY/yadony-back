package com.yadony.api.matching;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Lectures légères du classement des annonces (fragment de {@link AnnouncementRepository}).
 * Une projection par Criteria applique la même {@link Specification} que {@code findAll} :
 * mêmes filtres, même ordre, même découpe, sans entité ni collection chargée.
 */
public interface AnnouncementRankingQueries {

    /**
     * Pendant léger de {@code findAll(spec, pageable)} : mêmes lignes, dans le même ordre
     * (tri du {@code pageable}, ou celui posé par la spécification quand il est non trié),
     * limitées à la page demandée. Aucun COUNT.
     */
    List<RankingRow> findRankingRows(Specification<AnnouncementEntity> spec, Pageable pageable);

    /** Types de contenu acceptés des annonces données, en une requête (absentes = aucune entrée). */
    Map<UUID, List<String>> findAcceptedContentTypes(Collection<UUID> announcementIds);
}
