package com.yadony.api.payments.mobilemoney;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MobileMoneyPayoutAccountRepository extends JpaRepository<MobileMoneyPayoutAccountEntity, UUID> {

    List<MobileMoneyPayoutAccountEntity> findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID userId);

    Optional<MobileMoneyPayoutAccountEntity> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    Optional<MobileMoneyPayoutAccountEntity> findByUserIdAndProviderAndDeletedAtIsNull(UUID userId, String provider);
}
