package com.yadony.api.emailotp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface EmailOtpRepository extends JpaRepository<EmailOtpEntity, UUID> {

    @Query("SELECT COUNT(e) FROM EmailOtpEntity e WHERE e.email = :email AND e.createdAt > :since")
    long countByEmailSince(@Param("email") String email, @Param("since") LocalDateTime since);

    Optional<EmailOtpEntity> findTopByEmailAndUsedAtIsNullOrderByCreatedAtDesc(String email);

    /**
     * Somme des essais de vérification sur tous les tokens récents d'une adresse.
     * Le budget anti-brute-force doit survivre aux renvois de code : compter par
     * token permettrait 5 essais frais à chaque POST /send.
     */
    @Query("SELECT COALESCE(SUM(e.attempts), 0) FROM EmailOtpEntity e WHERE e.email = :email AND e.createdAt > :since")
    long sumAttemptsByEmailSince(@Param("email") String email, @Param("since") LocalDateTime since);

    /**
     * Compte un essai raté, dans sa propre transaction. La vérification lève juste après
     * pour refuser le code : dans la transaction de l'appelant, cette exception annulait
     * l'incrément et le budget d'essais ne bougeait jamais (code à 6 chiffres devinable
     * sans limite). L'incrément en SQL évite aussi de perdre un essai entre deux
     * vérifications simultanées.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE EmailOtpEntity e SET e.attempts = e.attempts + 1 WHERE e.id = :id")
    int incrementAttempts(@Param("id") UUID id);

    /** Envois toutes adresses confondues : budget global du canal (coût, réputation d'envoi). */
    @Query("SELECT COUNT(e) FROM EmailOtpEntity e WHERE e.createdAt > :since")
    long countAllSince(@Param("since") LocalDateTime since);

    /**
     * Invalide les codes encore actifs : à l'envoi d'un nouveau code (seul le dernier
     * reste valable) et après une vérification réussie (sinon le code précédent,
     * toujours dans sa validité, redevenait le « dernier non utilisé »).
     *
     * <p>Par expiration et non par {@code usedAt} : {@code usedAt} ne marque ainsi
     * qu'une connexion réussie, ce que compte la limite de connexions.
     */
    @Modifying
    @Query("UPDATE EmailOtpEntity e SET e.expiresAt = :now WHERE e.email = :email AND e.usedAt IS NULL AND e.expiresAt > :now")
    int invalidateActive(@Param("email") String email, @Param("now") LocalDateTime now);
}
