package com.yadony.api.payments.wallet.topupcode;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletTopupCodeRepository extends JpaRepository<WalletTopupCodeEntity, UUID> {

    /**
     * Recherche par empreinte, qui sérialise les utilisations d'un même code : la dernière unité
     * ne part qu'une fois.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from WalletTopupCodeEntity c where c.codeHash = :codeHash")
    Optional<WalletTopupCodeEntity> findByCodeHashForUpdate(@Param("codeHash") String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from WalletTopupCodeEntity c where c.id = :id")
    Optional<WalletTopupCodeEntity> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByCodeHash(String codeHash);

    List<WalletTopupCodeEntity> findAllByOrderByCreatedAtDesc();
}
