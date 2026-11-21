package com.yadony.api.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface ReleasedUsernameRepository extends JpaRepository<ReleasedUsernameEntity, String> {

    /** Cet identifiant est-il encore réservé (libéré après {@code threshold}) ? */
    boolean existsByUsernameAndReleasedAtAfter(String username, OffsetDateTime threshold);

    /** Purge les réservations expirées et renvoie leur nombre. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ReleasedUsernameEntity r WHERE r.releasedAt < :threshold")
    int deleteByReleasedAtBefore(@Param("threshold") OffsetDateTime threshold);
}
