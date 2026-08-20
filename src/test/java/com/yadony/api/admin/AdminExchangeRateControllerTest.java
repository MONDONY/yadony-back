package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.common.AuditService;
import com.yadony.api.payments.currency.ExchangeRateEntity;
import com.yadony.api.payments.currency.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tache 11 — /admin/exchange-rates.
 *
 * <p>Le cache {@code exchange-rates} est le vrai bean Caffeine du contexte (pas un mock) : c'est
 * la seule facon de verifier qu'une eviction a reellement eu lieu, et non simplement qu'une
 * methode a ete appelee.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminExchangeRateControllerTest — /admin/exchange-rates")
class AdminExchangeRateControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired CacheManager cacheManager;

    @MockitoBean ExchangeRateRepository exchangeRateRepository;
    @MockitoBean AuditService auditService;

    private static final UUID ADMIN_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-exrates");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    /** Aucune ROLE_ADMIN — utilisateur business ordinaire. */
    private static UsernamePasswordAuthenticationToken nonAdminAuth() {
        return new UsernamePasswordAuthenticationToken("expediteur", null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static ExchangeRateEntity usdRate() {
        ExchangeRateEntity entity = new ExchangeRateEntity("USD", new BigDecimal("1.080000"));
        return entity;
    }

    @BeforeEach
    void clearCache() {
        Cache cache = cacheManager.getCache("exchange-rates");
        if (cache != null) {
            cache.clear();
        }
    }

    // ── Permission ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET — sans ROLE_ADMIN -> 403")
    void get_withoutAdminRole_returns403() throws Exception {
        mockMvc.perform(get("/admin/exchange-rates").with(authentication(nonAdminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT — sans ROLE_ADMIN -> 403 et rien n'est ecrit")
    void put_withoutAdminRole_returns403() throws Exception {
        mockMvc.perform(put("/admin/exchange-rates/USD")
                        .with(authentication(nonAdminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitsPerEur\":\"1.10\"}"))
                .andExpect(status().isForbidden());

        verify(exchangeRateRepository, never()).save(any());
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET — ADMIN -> 200, la liste des taux")
    void get_withAdmin_returnsList() throws Exception {
        when(exchangeRateRepository.findAll()).thenReturn(List.of(usdRate()));

        mockMvc.perform(get("/admin/exchange-rates").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].unitsPerEur").value(1.08));
    }

    // ── PUT succes ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT — ADMIN -> 200, updated_by porte l'admin appelant et une entree audit_log est ecrite")
    void put_withAdmin_writesUpdatedByAndAuditLog() throws Exception {
        ExchangeRateEntity existing = usdRate();
        when(exchangeRateRepository.findByCurrency("USD")).thenReturn(Optional.of(existing));
        when(exchangeRateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/admin/exchange-rates/USD")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitsPerEur\":\"1.15\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.unitsPerEur").value(1.15))
                .andExpect(jsonPath("$.updatedBy").value(ADMIN_ID.toString()));

        assertThat(existing.getUpdatedBy()).isEqualTo(ADMIN_ID);
        assertThat(existing.getUnitsPerEur()).isEqualByComparingTo("1.15");

        verify(auditService).log(eq("EXCHANGE_RATE"), eq(null), eq("EXCHANGE_RATE_UPDATED"), eq(ADMIN_ID), any());
    }

    @Test
    @DisplayName("PUT — evince le cache exchange-rates pour que rateOf/convert reflete immediatement le nouveau taux")
    void put_evictsExchangeRatesCache() throws Exception {
        Cache cache = cacheManager.getCache("exchange-rates");
        assertThat(cache).isNotNull();
        cache.put("USD", new BigDecimal("1.08"));
        assertThat(cache.get("USD")).isNotNull();

        ExchangeRateEntity existing = usdRate();
        when(exchangeRateRepository.findByCurrency("USD")).thenReturn(Optional.of(existing));
        when(exchangeRateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/admin/exchange-rates/USD")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitsPerEur\":\"1.20\"}"))
                .andExpect(status().isOk());

        assertThat(cache.get("USD")).isNull();
    }

    // ── PUT validation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT — taux negatif -> 422 exchange-rate-not-positive, rien n'est ecrit")
    void put_negativeRate_returns422() throws Exception {
        mockMvc.perform(put("/admin/exchange-rates/USD")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitsPerEur\":\"-1.10\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("exchange-rate-not-positive"));

        verify(exchangeRateRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PUT — taux nul -> 422 exchange-rate-not-positive, rien n'est ecrit")
    void put_zeroRate_returns422() throws Exception {
        mockMvc.perform(put("/admin/exchange-rates/USD")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitsPerEur\":\"0\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("exchange-rate-not-positive"));

        verify(exchangeRateRepository, never()).save(any());
    }

    @Test
    @DisplayName("PUT — unitsPerEur absent -> 422 exchange-rate-required, rien n'est ecrit")
    void put_missingRate_returns422() throws Exception {
        mockMvc.perform(put("/admin/exchange-rates/USD")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("exchange-rate-required"));

        verify(exchangeRateRepository, never()).save(any());
    }

    @Test
    @DisplayName("PUT — taux absurde -> 422 exchange-rate-out-of-range, rien n'est ecrit")
    void put_absurdRate_returns422() throws Exception {
        mockMvc.perform(put("/admin/exchange-rates/USD")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitsPerEur\":\"999999\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("exchange-rate-out-of-range"));

        verify(exchangeRateRepository, never()).save(any());
    }

    // ── PUT parite fixe XOF/XAF ──────────────────────────────────────────────

    @Test
    @DisplayName("PUT /XOF — refuse en 422 exchange-rate-fixed-parity, rien n'est ecrit")
    void put_xof_returns422FixedParity() throws Exception {
        mockMvc.perform(put("/admin/exchange-rates/XOF")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitsPerEur\":\"700\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("exchange-rate-fixed-parity"));

        verify(exchangeRateRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PUT /XAF — refuse en 422 exchange-rate-fixed-parity, rien n'est ecrit")
    void put_xaf_returns422FixedParity() throws Exception {
        mockMvc.perform(put("/admin/exchange-rates/xaf")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitsPerEur\":\"700\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("exchange-rate-fixed-parity"));

        verify(exchangeRateRepository, never()).save(any());
    }

    @Test
    @DisplayName("PUT — devise inconnue en base -> 404, rien n'est ecrit")
    void put_unknownCurrency_returns404() throws Exception {
        when(exchangeRateRepository.findByCurrency("ZZZ")).thenReturn(Optional.empty());

        mockMvc.perform(put("/admin/exchange-rates/ZZZ")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitsPerEur\":\"1.10\"}"))
                .andExpect(status().isNotFound());

        verify(exchangeRateRepository, never()).save(any());
    }
}
