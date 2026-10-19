package com.yadony.api.requests.service;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.StorageService;
import com.yadony.api.config.PlatformSettingsTestFactory;
import com.yadony.api.requests.entity.PackageRequestEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PackageRequestSearchMapperGpProTest {

    @Test
    void packageRequestSenderExposesGpPro() {
        PackageRequestSearchMapper mapper = new PackageRequestSearchMapper(
                mock(com.yadony.api.auth.UserRepository.class), mock(com.yadony.api.city.CityRepository.class),
                mock(StorageService.class), mock(PackageRequestPhotoService.class),
                PlatformSettingsTestFactory.withUrgencyThresholdDays(3),
                mock(com.yadony.api.common.commission.CommissionGridService.class));
        UserEntity sender = new UserEntity();
        sender.setUsername("user1");
        sender.setGpPro(true);
        UUID senderId = UUID.randomUUID();
        PackageRequestEntity entity = new PackageRequestEntity();
        entity.setSenderId(senderId);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());

        var response = mapper.toSearchResponse(entity, false,
                ViewerPaymentCapabilities.NONE,
                Map.of(senderId, sender), Map.of(), Map.of());

        assertThat(response.sender().gpPro()).isTrue();
    }
}
