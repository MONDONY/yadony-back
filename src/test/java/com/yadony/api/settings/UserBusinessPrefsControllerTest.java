package com.yadony.api.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UserBusinessPrefsControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean UserBusinessPrefsService service;

    private static final String FIREBASE_UID = "uid-test";

    private UsernamePasswordAuthenticationToken asUser() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void getPrefs_authenticated_returnsDefaults() throws Exception {
        when(service.getPrefs(FIREBASE_UID)).thenReturn(UserBusinessPrefsDto.defaults());
        mockMvc.perform(get("/users/me/business-preferences").with(authentication(asUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightUnit").value("kg"))
                .andExpect(jsonPath("$.currencyCode").value("EUR"))
                .andExpect(jsonPath("$.pickupRadiusKm").value(10))
                .andExpect(jsonPath("$.defaultPackageWeightKg").value(23))
                .andExpect(jsonPath("$.minBidPriceEur").value(0));
    }

    @Test
    void getPrefs_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/users/me/business-preferences"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putPrefs_valid_returns200() throws Exception {
        UserBusinessPrefsDto dto = new UserBusinessPrefsDto("lbs", "XOF", 20, 30, 5, "call", 2, null);
        when(service.upsert(eq(FIREBASE_UID), any())).thenReturn(dto);
        mockMvc.perform(put("/users/me/business-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .with(authentication(asUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightUnit").value("lbs"))
                .andExpect(jsonPath("$.contactMode").value("call"))
                .andExpect(jsonPath("$.responseDelayHours").value(2));
    }

    @Test
    void putPrefs_rejectsCurrencyRemovedFromCatalog() throws Exception {
        // Catalogue réduit à EUR/XOF/XAF le 2026-08-19 (zéro compte réel en prod à
        // cette date) — voir docs/specs/2026-08-19-plan-implementation-multidevise.md,
        // lot 1. CAD était accepté avant cette réduction.
        UserBusinessPrefsDto dto = new UserBusinessPrefsDto("kg", "CAD", 20, 30, 5, "call", 2, null);

        mockMvc.perform(put("/users/me/business-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .with(authentication(asUser())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void putPrefs_invalidEnum_returns422() throws Exception {
        String body = "{\"weightUnit\":\"ton\",\"currencyCode\":\"EUR\",\"pickupRadiusKm\":10,\"defaultPackageWeightKg\":23,\"minBidPriceEur\":0}";
        mockMvc.perform(put("/users/me/business-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(asUser())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void putPrefs_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/users/me/business-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserBusinessPrefsDto.defaults())))
                .andExpect(status().isUnauthorized());
    }
}
