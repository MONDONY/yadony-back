package com.yadony.api.admin.broadcast.segment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface BroadcastSegmentRepository extends JpaRepository<BroadcastSegmentEntity, UUID> {

    Page<BroadcastSegmentEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    /** Segments vivants (le @Where écarte les supprimés) portant ce nom, sans tenir compte de la casse. */
    @Query("SELECT COUNT(s) > 0 FROM BroadcastSegmentEntity s WHERE LOWER(s.name) = LOWER(:name)")
    boolean existsByNameIgnoreCase(String name);

    @Query("SELECT COUNT(s) > 0 FROM BroadcastSegmentEntity s WHERE LOWER(s.name) = LOWER(:name) AND s.id <> :id")
    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
