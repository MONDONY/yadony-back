package com.yadony.api.kyc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.yadony.api.kyc.provider.VerificationProviderKind;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KycRepository extends JpaRepository<KycVerificationEntity, UUID> {

    Optional<KycVerificationEntity> findByUserId(UUID userId);

    Optional<KycVerificationEntity> findByVerificationSessionId(String sessionId);

    /**
     * Sessions Didit ouvertes dont l'utilisateur attend encore (users.kyc_status PENDING), sans
     * decision d'administrateur, modifiees entre {@code after} et {@code before} : celles dont le
     * webhook a pu se perdre. Les plus recentes d'abord. Une ligne repassee a NOT_STARTED par un
     * abandon n'attend plus rien et est exclue.
     */
    @Query("""
            select k from KycVerificationEntity k
            where k.status = com.yadony.api.kyc.KycVerificationStatus.PENDING
              and k.provider = :provider
              and k.verificationSessionId is not null
              and k.decisionKind is null
              and k.updatedAt < :before and k.updatedAt > :after
              and exists (select 1 from UserEntity u where u.id = k.userId
                          and u.kycStatus = com.yadony.api.auth.KycStatus.PENDING)
            order by k.updatedAt desc
            """)
    List<KycVerificationEntity> findStalePending(@Param("provider") VerificationProviderKind provider,
                                                 @Param("before") LocalDateTime before,
                                                 @Param("after") LocalDateTime after,
                                                 Pageable page);

    /** Revues manuelles chez le fournisseur en attente depuis avant {@code before}. */
    @Query("""
            select k from KycVerificationEntity k
            where k.status = com.yadony.api.kyc.KycVerificationStatus.PENDING
              and k.provider = :provider
              and k.submittedAt is not null
              and k.submittedAt < :before
              and k.decisionKind is null
            order by k.submittedAt
            """)
    List<KycVerificationEntity> findInReviewSince(@Param("provider") VerificationProviderKind provider,
                                                  @Param("before") LocalDateTime before,
                                                  Pageable page);
}
