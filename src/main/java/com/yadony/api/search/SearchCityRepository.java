package com.yadony.api.search;

import com.yadony.api.city.CityEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Lecture directe de la table {@code cities} pour la résolution floue.
 *
 * <p>Le package {@code search} n'injecte pas {@code CityService} : la règle projet
 * interdit l'injection de service entre packages. Lire l'entité par un repository
 * propre est le même contournement que celui d'{@code AnnouncementSpecification},
 * qui interroge {@code UserEntity} en sous-requête.
 *
 * <p>{@code similarity()} vient de {@code pg_trgm}, installé par la migration V51,
 * qui a aussi posé {@code idx_cities_name_trigram}. L'extension {@code unaccent}
 * n'est pas disponible et ne doit pas l'être (pas de migration dans ce lot) — mais
 * les trigrammes n'absorbent PAS l'absence d'accent à eux seuls : {@code :token}
 * arrive déjà désaccentué par {@link SearchTokenizer#normalize}, alors que
 * {@code name} garde ses accents réels en base. Sans repli, « lome » (saisie
 * plausible sans clavier français) battait « Lomé » (Togo, 2,2M hab.) avec
 * « Lomme » (France, 30k hab.) — vérifié empiriquement contre la vraie base
 * ({@code SearchCityRepositoryIT}), pas une hypothèse. {@code translate()} est du
 * SQL standard (aucune extension) : replier ici les diacritiques latins les plus
 * courants sur le corridor du produit (français + Afrique francophone) suffit à
 * remettre les deux côtés de la comparaison sur un pied d'égalité.
 */
public interface SearchCityRepository extends Repository<CityEntity, Long> {

    record CityMatch(String name, double similarity, long population, String countryCode) {}

    /** Diacritiques latins → forme sans accent, dans le même ordre (position à position). */
    String ACCENTED = "àâäáãåèéêëìíîïòóôöõùúûüçñýÿ";
    String UNACCENTED = "aaaaaaeeeeiiiioooooouuuucnyy";

    @Query(value = """
        SELECT name              AS name,
               similarity(translate(LOWER(name), :accented, :unaccented), :token) AS similarity,
               population        AS population,
               country_code      AS countryCode
        FROM cities
        WHERE similarity(translate(LOWER(name), :accented, :unaccented), :token) >= :threshold
        ORDER BY similarity DESC, population DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<CityMatch> findSimilarRaw(
            @Param("token") String token,
            @Param("threshold") double threshold,
            @Param("limit") int limit,
            @Param("accented") String accented,
            @Param("unaccented") String unaccented);

    default List<CityMatch> findSimilar(String token, double threshold, int limit) {
        return findSimilarRaw(token, threshold, limit, ACCENTED, UNACCENTED);
    }
}
