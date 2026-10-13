package com.yadony.api.requests.repository;

import com.yadony.api.requests.entity.NegotiationMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NegotiationMessageRepository extends JpaRepository<NegotiationMessageEntity, UUID> {
    List<NegotiationMessageEntity> findByThreadIdOrderByCreatedAtAsc(UUID threadId);

    /**
     * Dernier message de chaque fil, en une requête : sert à savoir à qui revient la main
     * sans charger tout l'historique des fils (recherche des demandes, FLUTTER-NZ).
     *
     * <p>Deux messages d'un même fil au même horodatage rendraient deux lignes : l'appelant
     * garde la première, ce cas n'existant pas en pratique (une action par requête).
     */
    @Query("""
        SELECT m FROM NegotiationMessageEntity m
        WHERE m.threadId IN :threadIds
          AND m.createdAt = (SELECT MAX(m2.createdAt) FROM NegotiationMessageEntity m2
                             WHERE m2.threadId = m.threadId)
    """)
    List<NegotiationMessageEntity> findLastByThreadIdIn(@Param("threadIds") Collection<UUID> threadIds);
}
