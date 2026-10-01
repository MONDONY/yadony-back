package com.yadony.api.matching.reception;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BidRecipientLinkRepository extends JpaRepository<BidRecipientLinkEntity, UUID> {

    Optional<BidRecipientLinkEntity> findByBidId(UUID bidId);

    boolean existsByBidId(UUID bidId);

    Optional<BidRecipientLinkEntity> findByBidIdAndRecipientUserId(UUID bidId, UUID recipientUserId);

    boolean existsByBidIdAndRecipientUserIdAndStatus(UUID bidId, UUID recipientUserId, ReceptionLinkStatus status);

    List<BidRecipientLinkEntity> findByRecipientUserIdAndStatusIn(UUID recipientUserId,
                                                                  Collection<ReceptionLinkStatus> statuses);

    /**
     * Colis actifs pouvant être rattachés après coup à {@code userId} : sans lien, dont il
     * n'est ni l'expéditeur ni le voyageur. Le numéro est comparé en Java
     * ({@link ReceptionPhones#digitsKey}) : il est saisi librement (espaces, tirets…), et
     * {@code regexp_replace} n'a pas la même syntaxe sous H2. Le filtre sur le dernier
     * chiffre ({@code suffix}) réduit la liste sans rien écarter de comparable.
     */
    @Query("""
            SELECT b FROM BidEntity b
            WHERE b.status IN :statuses
              AND b.recipientPhone IS NOT NULL
              AND TRIM(b.recipientPhone) LIKE :suffix
              AND b.senderId <> :userId
              AND NOT EXISTS (SELECT 1 FROM BidRecipientLinkEntity l WHERE l.bidId = b.id)
              AND NOT EXISTS (SELECT 1 FROM AnnouncementEntity a
                              WHERE a.id = b.announcementId AND a.travelerId = :userId)
            """)
    List<BidEntity> findCatchUpCandidates(@Param("userId") UUID userId,
                                          @Param("statuses") Collection<BidStatus> statuses,
                                          @Param("suffix") String suffix);
}
