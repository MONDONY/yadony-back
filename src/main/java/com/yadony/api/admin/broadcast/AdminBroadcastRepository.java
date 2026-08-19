package com.yadony.api.admin.broadcast;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AdminBroadcastRepository extends JpaRepository<AdminBroadcastEntity, UUID> {

    /** Historique, le plus recent d'abord. Le @Where de l'entite ecarte les lignes supprimees. */
    Page<AdminBroadcastEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
