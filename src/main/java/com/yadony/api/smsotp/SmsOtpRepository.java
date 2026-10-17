package com.yadony.api.smsotp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SmsOtpRepository extends JpaRepository<SmsOtpEntity, UUID> {

    @Query("SELECT COUNT(s) FROM SmsOtpEntity s WHERE s.phoneNumber = :phone AND s.createdAt > :since")
    long countByPhoneSince(@Param("phone") String phone, @Param("since") LocalDateTime since);

    Optional<SmsOtpEntity> findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(String phone);

    /**
     * Somme des essais de vérification sur tous les tokens récents d'un numéro.
     * Le budget anti-brute-force doit survivre aux renvois de code : compter par
     * token permettrait 5 essais frais à chaque POST /send.
     */
    @Query("SELECT COALESCE(SUM(s.attempts), 0) FROM SmsOtpEntity s WHERE s.phoneNumber = :phone AND s.createdAt > :since")
    long sumAttemptsByPhoneSince(@Param("phone") String phone, @Param("since") LocalDateTime since);

    /**
     * Compte un essai raté, dans sa propre transaction. La vérification lève juste après
     * pour refuser le code : dans la transaction de l'appelant, cette exception annulait
     * l'incrément et le budget d'essais ne bougeait jamais (code à 6 chiffres devinable
     * sans limite). L'incrément en SQL évite aussi de perdre un essai entre deux
     * vérifications simultanées.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE SmsOtpEntity s SET s.attempts = s.attempts + 1 WHERE s.id = :id")
    int incrementAttempts(@Param("id") UUID id);

    /** Envois toutes adresses confondues : budget global du canal (coût, réputation d'envoi). */
    @Query("SELECT COUNT(s) FROM SmsOtpEntity s WHERE s.createdAt > :since")
    long countAllSince(@Param("since") LocalDateTime since);

    /**
     * Invalide les codes encore actifs : à l'envoi d'un nouveau code (seul le dernier
     * reste valable) et après une vérification réussie (sinon le code précédent,
     * toujours dans sa validité, redevenait le « dernier non utilisé »).
     */
    @Modifying
    @Query("UPDATE SmsOtpEntity s SET s.usedAt = :now WHERE s.phoneNumber = :phone AND s.usedAt IS NULL")
    int invalidateActive(@Param("phone") String phone, @Param("now") LocalDateTime now);
}
