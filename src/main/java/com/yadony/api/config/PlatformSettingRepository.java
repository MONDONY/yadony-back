package com.yadony.api.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlatformSettingRepository extends JpaRepository<PlatformSettingEntity, UUID> {

    Optional<PlatformSettingEntity> findBySettingKey(String settingKey);
}
