package com.yadony.api.config;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatformSettingsPhotoKeysTest {

    @Mock PlatformSettingRepository repository;
    @Mock PlatformSettingsCache cache;
    @Mock AuditService auditService;
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) YadonyConfigProperties config;

    PlatformSettingsService service;

    @BeforeEach
    void setUp() {
        service = new PlatformSettingsService(repository, cache, auditService, config,
                false, false, "", false);
    }

    @Test
    void defaults_whenRowsMissing() {
        when(cache.all()).thenReturn(Map.of());
        assertThat(service.photoRetentionDays()).isEqualTo(7);
        assertThat(service.photoUploadDailyQuota()).isEqualTo(60);
        assertThat(service.trackingPhotosMaxPerBid()).isEqualTo(6);
        assertThat(service.photoPurgeEnabled()).isFalse();
    }

    @Test
    void readsStoredValues() {
        when(cache.all()).thenReturn(Map.of(
                "photo_retention_days_after_delivery", "30",
                "photo_upload_daily_quota", "100",
                "tracking_photos_max_per_bid", "4",
                "photo_purge_enabled", "true"));
        assertThat(service.photoRetentionDays()).isEqualTo(30);
        assertThat(service.photoUploadDailyQuota()).isEqualTo(100);
        assertThat(service.trackingPhotosMaxPerBid()).isEqualTo(4);
        assertThat(service.photoPurgeEnabled()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "photo_retention_days_after_delivery, 0",
            "photo_retention_days_after_delivery, 366",
            "photo_retention_days_after_delivery, abc",
            "photo_upload_daily_quota, 4",
            "photo_upload_daily_quota, 1001",
            "tracking_photos_max_per_bid, 0",
            "tracking_photos_max_per_bid, 21",
            "photo_purge_enabled, oui"
    })
    void rejectsOutOfBounds_andWritesNothing(String key, String value) {
        assertThatThrownBy(() -> service.updateOne(PlatformSettingKey.fromKey(key), value, UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void acceptsBoundsAndAudits() {
        PlatformSettingEntity row = new PlatformSettingEntity("photo_retention_days_after_delivery", "7",
                PlatformSettingType.INTEGER);
        when(repository.findBySettingKey("photo_retention_days_after_delivery")).thenReturn(Optional.of(row));
        when(repository.findAll()).thenReturn(java.util.List.of(row));
        when(cache.all()).thenReturn(Map.of());

        service.updateOne(PlatformSettingKey.PHOTO_RETENTION_DAYS_AFTER_DELIVERY, " 365 ", UUID.randomUUID());

        assertThat(row.getSettingValue()).isEqualTo("365");
        verify(auditService).log(eq("platform_setting"), any(), eq("PLATFORM_SETTING_CHANGED"), any(), any());
    }
}
