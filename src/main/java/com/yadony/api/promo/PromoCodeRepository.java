package com.yadony.api.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface PromoCodeRepository extends JpaRepository<PromoCodeEntity, UUID> {

    Optional<PromoCodeEntity> findByCode(String code);

    /**
     * Code déjà pris, y compris par un code supprimé : l'unicité de {@code promo_codes.code}
     * porte sur toutes les lignes, alors que les requêtes JPA filtrent {@code deleted_at}.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM promo_codes WHERE code = :code)", nativeQuery = true)
    boolean existsByCodeIncludingDeleted(@Param("code") String code);

    /** Verrou pessimiste pour incrémenter redeemedCount sans race. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PromoCodeEntity p WHERE p.id = :id")
    Optional<PromoCodeEntity> findByIdForUpdate(UUID id);

    /**
     * Compteur lu directement en base. À appeler sous {@link #findByIdForUpdate} : l'entité
     * gérée renvoyée par ce verrou peut être celle déjà chargée dans le contexte de
     * persistance avant le verrou, et Hibernate ne rafraîchit pas son état.
     */
    @Query("SELECT p.redeemedCount FROM PromoCodeEntity p WHERE p.id = :id")
    int findRedeemedCountById(UUID id);
}
