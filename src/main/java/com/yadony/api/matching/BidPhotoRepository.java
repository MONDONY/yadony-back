package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BidPhotoRepository extends JpaRepository<BidPhotoEntity, UUID> {

    List<BidPhotoEntity> findByBidIdAndStatusOrderByPositionAsc(UUID bidId, BidPhotoStatus status);

    List<BidPhotoEntity> findByStatus(BidPhotoStatus status);

    /** Rapprochement R2 : clés de toutes les photos de bid, tous statuts (DELETING compris). */
    @Query("SELECT p.objectKey FROM BidPhotoEntity p WHERE p.objectKey LIKE CONCAT(:prefix, '%') "
            + "OR p.objectKey LIKE CONCAT('%/', :prefix, '%')")
    List<String> findObjectKeyValuesMatching(@Param("prefix") String prefix);
}
