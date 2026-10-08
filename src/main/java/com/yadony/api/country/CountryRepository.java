package com.yadony.api.country;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CountryRepository extends JpaRepository<CountryEntity, String> {

    /**
     * Crée le pays s'il n'existe pas, de façon atomique : deux premières annonces
     * simultanées vers un même pays ne violent plus la clé primaire (V129). Un
     * {@code save()} suivi d'un catch ne protégeait rien, l'INSERT partant au commit de
     * la transaction appelante (création d'annonce).
     *
     * @return 1 si la ligne a été insérée, 0 si elle existait déjà
     */
    @Modifying
    @Query(value = """
            INSERT INTO countries (country_code, country_name, flag, created_at, updated_at)
            VALUES (:code, :name, :flag, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("code") String code,
                       @Param("name") String name,
                       @Param("flag") String flag);
}
