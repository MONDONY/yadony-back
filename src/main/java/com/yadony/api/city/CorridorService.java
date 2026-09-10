package com.yadony.api.city;

import com.yadony.api.city.dto.PopularCorridorResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CorridorService {

    /** Cache déclaré dans {@code CacheConfig} : TTL courte, sans éviction manuelle. */
    static final String CACHE_NAME = "popular-corridors";

    private static final int MAX_LIMIT = 20;
    private final CorridorRepository corridorRepository;

    public CorridorService(CorridorRepository corridorRepository) {
        this.corridorRepository = corridorRepository;
    }

    /**
     * Corridors les plus utilisés, affichés à l'ouverture des formulaires de publication.
     * Volontairement sans {@code @CacheEvict} sur {@link #upsertCorridor} : chaque
     * publication incrémente un compteur, évincer à chaque écriture viderait le cache
     * en permanence alors qu'un classement en retard d'une minute est invisible.
     */
    @Cacheable(cacheNames = CACHE_NAME, key = "T(com.yadony.api.city.CorridorService).clampLimit(#limit)")
    public List<PopularCorridorResponse> getPopular(int limit) {
        int effective = clampLimit(limit);
        return corridorRepository.findTopByUsageCount(effective).stream()
            .map(e -> new PopularCorridorResponse(
                e.getDepartureCity(),
                e.getDepartureCountry(),
                e.getArrivalCity(),
                e.getArrivalCountry()
            ))
            .toList();
    }

    /**
     * Borne la limite à [1, MAX_LIMIT] ; sert aussi de clé de cache (publique : évaluée par
     * SpEL depuis l'annotation), toutes les valeurs hors bornes partageant l'entrée du
     * plafond ou du plancher.
     */
    public static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    @Transactional
    public void upsertCorridor(String depCity, String depCountry,
                                String arrCity, String arrCountry) {
        if (corridorRepository.existsByDepartureCityAndArrivalCity(depCity, arrCity)) {
            corridorRepository.incrementUsageCount(depCity, arrCity);
        } else {
            CorridorEntity entity = new CorridorEntity();
            entity.setDepartureCity(depCity);
            entity.setDepartureCountry(depCountry != null ? depCountry : "");
            entity.setArrivalCity(arrCity);
            entity.setArrivalCountry(arrCountry != null ? arrCountry : "");
            corridorRepository.save(entity);
        }
    }
}
