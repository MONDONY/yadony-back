package com.yadony.api.common.media;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PhotoPurgeRunRepository extends JpaRepository<PhotoPurgeRunEntity, UUID> {

    List<PhotoPurgeRunEntity> findTop5ByKindOrderByStartedAtDesc(String kind);

    Optional<PhotoPurgeRunEntity> findTopByKindAndErrorIsNullAndFinishedAtIsNotNullOrderByStartedAtDesc(String kind);
}
