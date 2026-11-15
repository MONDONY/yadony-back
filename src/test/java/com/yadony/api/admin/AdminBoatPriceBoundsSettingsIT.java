package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.config.PlatformSettingKey;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bornes du prix au m³ du bateau (lot 4) réglées depuis le back-office, avec le vrai service des réglages. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("PUT /admin/settings — bornes du prix au m³ du bateau")
class AdminBoatPriceBoundsSettingsIT {

    @Autowired MockMvc mockMvc;
    @Autowired PlatformSettingsService settings;

    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-boat-prices");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("CONFIG_MANAGE")));
    }

    private org.springframework.test.web.servlet.ResultActions putSetting(String key, String value) throws Exception {
        return mockMvc.perform(put("/admin/settings/" + key)
                .with(authentication(adminAuth()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + value + "\"}"));
    }

    @AfterEach
    void restore() {
        settings.update(Map.of(
                PlatformSettingKey.BOAT_PRICE_PER_M3_HARD_MIN_EUR, "100",
                PlatformSettingKey.BOAT_PRICE_PER_M3_HARD_MAX_EUR, "600",
                PlatformSettingKey.BOAT_PRICE_PER_M3_ADVISED_MIN_EUR, "150",
                PlatformSettingKey.BOAT_PRICE_PER_M3_ADVISED_MAX_EUR, "400",
                PlatformSettingKey.BOAT_PRICE_PER_M3_REFERENCE_EUR, "240"), UUID.randomUUID());
    }

    @Test
    @DisplayName("borne minimale au-dessus de la zone conseillée : 422 platform-setting-invalid, rien d'écrit")
    void put_breaksTheOrder_422() throws Exception {
        putSetting("boat_price_per_m3_hard_min_eur", "200")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("platform-setting-invalid"));

        assertThat(settings.boatPricePerM3Bounds().hardMinEur()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("hors des bornes de saisie (1 à 100 000) : 422 platform-setting-invalid")
    void put_outOfInputRange_422() throws Exception {
        putSetting("boat_price_per_m3_hard_max_eur", "100001")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("platform-setting-invalid"));
        putSetting("boat_price_per_m3_reference_eur", "abc")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("platform-setting-invalid"));
    }

    @Test
    @DisplayName("valeur cohérente : écrite, normalisée et lue aussitôt")
    void put_valid_200() throws Exception {
        putSetting("boat_price_per_m3_hard_max_eur", "800.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("boat_price_per_m3_hard_max_eur"))
                .andExpect(jsonPath("$.value").value("800"))
                .andExpect(jsonPath("$.type").value("DECIMAL"));

        assertThat(settings.boatPricePerM3Bounds().hardMaxEur()).isEqualByComparingTo("800");
    }
}
