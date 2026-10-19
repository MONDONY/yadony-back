package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.commission.ProviderCostProperties;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridDraft;
import com.yadony.api.common.commission.CommissionGridHistoryEntry;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionGridWarning;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrat HTTP de l'onglet Tarification. Le service est mocké : sous H2 les colonnes jsonb de
 * {@code commission_grids} ne se relisent pas, et ses règles sont testées dans {@code CommissionGridServiceTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminCommissionGridControllerIT — /admin/commission-grids")
class AdminCommissionGridControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 1, 0, 0);

    @Autowired MockMvc mockMvc;
    @MockitoBean CommissionGridService gridService;
    @Autowired ProviderCostProperties costs;

    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-grid");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("CONFIG_MANAGE")));
    }

    /** SUPPORT porte ROLE_ADMIN comme tout compte admin : seule l'authority discrimine. */
    private static UsernamePasswordAuthenticationToken supportAuth() {
        AdminPrincipal principal = new AdminPrincipal(UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT, false, "uid-support-grid");
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static CommissionGrid grid(UUID id) {
        return new CommissionGrid(id, T0,
                List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                        new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06"))),
                new EnumMap<>(CommissionGridService.defaultMinimums()));
    }

    private static final String VALID_BODY = """
            {"tiers":[{"fromEur":0,"rate":0.07},{"fromEur":100,"rate":0.06}],
             "minimums":{"EUR":1.00,"XOF":650,"XAF":650,"USD":1.10,"CAD":1.50,"GBP":0.90,"CHF":0.95},
             "effectiveFrom":"2026-11-01T00:00:00","note":"Nouvelle grille","acknowledgedWarnings":[%s]}""";

    @Test
    @DisplayName("application.yml porte les coûts Stripe par défaut du simulateur")
    void providerCosts_boundFromApplicationYml() {
        assertThat(costs.cardStandardPercent()).isEqualByComparingTo("1.5");
        assertThat(costs.cardPremiumPercent()).isEqualByComparingTo("2.8");
        assertThat(costs.cardInternationalPercent()).isEqualByComparingTo("3.15");
        assertThat(costs.cardFixedEur()).isEqualByComparingTo("0.25");
        assertThat(costs.connectPayoutPercent()).isEqualByComparingTo("0.25");
        assertThat(costs.connectPayoutFixedEur()).isEqualByComparingTo("0.10");
        assertThat(costs.connectActiveAccountMonthlyEur()).isEqualByComparingTo("2.0");
    }

    // ── POST ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST — SUPPORT (sans CONFIG_MANAGE) → 403, rien n'est programmé")
    void post_withoutConfigManage_403() throws Exception {
        mockMvc.perform(post("/admin/commission-grids").with(authentication(supportAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY.formatted("")))
                .andExpect(status().isForbidden());

        verify(gridService, never()).schedule(any(), any(), any());
    }

    @Test
    @DisplayName("POST — grille valide → 201, la grille et ses avertissements ; le brouillon et l'admin arrivent au service")
    void post_valid_201() throws Exception {
        UUID id = UUID.randomUUID();
        when(gridService.warningsFor(any())).thenReturn(List.of(CommissionGridWarning.INCREASE_WITHIN_30_DAYS));
        when(gridService.schedule(any(), any(), any())).thenReturn(grid(id));

        mockMvc.perform(post("/admin/commission-grids").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY.formatted("\"INCREASE_WITHIN_30_DAYS\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.tiers.length()").value(2))
                .andExpect(jsonPath("$.minimums.XOF").value(650))
                .andExpect(jsonPath("$.warnings[0]").value("INCREASE_WITHIN_30_DAYS"));

        ArgumentCaptor<CommissionGridDraft> draft = ArgumentCaptor.forClass(CommissionGridDraft.class);
        verify(gridService).schedule(draft.capture(), eq(Set.of(CommissionGridWarning.INCREASE_WITHIN_30_DAYS)), eq(ADMIN_ID));
        assertThat(draft.getValue().minimums()).containsEntry(SupportedCurrency.XOF, new BigDecimal("650"));
        assertThat(draft.getValue().note()).isEqualTo("Nouvelle grille");
        assertThat(draft.getValue().effectiveFrom()).isEqualTo(T0);
    }

    @Test
    @DisplayName("POST — grille invalide → 422 application/problem+json avec les violations du service")
    void post_invalid_422ProblemJson() throws Exception {
        when(gridService.warningsFor(any())).thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "validation", "Validation Error", "Grille de commission invalide",
                Map.of("violations", Map.of("tiers[0].fromEur", "La première borne vaut 0."))));

        mockMvc.perform(post("/admin/commission-grids").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY.formatted("")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.violations['tiers[0].fromEur']").value("La première borne vaut 0."));
    }

    @Test
    @DisplayName("POST — avertissement ou devise inconnus → 422 validation, le service n'est pas appelé")
    void post_unknownEnumNames_422() throws Exception {
        mockMvc.perform(post("/admin/commission-grids").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY.formatted("\"NOPE\"")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("validation")))
                .andExpect(jsonPath("$.violations.acknowledgedWarnings").exists());

        mockMvc.perform(post("/admin/commission-grids").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tiers\":[],\"minimums\":{\"JPY\":1},\"acknowledgedWarnings\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations['minimums.JPY']").exists());

        verify(gridService, never()).schedule(any(), any(), any());
    }

    @Test
    @DisplayName("POST — hausse non acquittée → 422 commission-grid-warning-not-acknowledged avec les avertissements")
    void post_increaseNotAcknowledged_422() throws Exception {
        when(gridService.warningsFor(any())).thenReturn(List.of(CommissionGridWarning.INCREASE_WITHIN_30_DAYS));
        when(gridService.schedule(any(), any(), any())).thenThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, "commission-grid-warning-not-acknowledged",
                "Commission Grid Warning Not Acknowledged", "acquittez l'avertissement",
                Map.of("warnings", List.of("INCREASE_WITHIN_30_DAYS"))));

        mockMvc.perform(post("/admin/commission-grids").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY.formatted("")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("commission-grid-warning-not-acknowledged")))
                .andExpect(jsonPath("$.warnings[0]").value("INCREASE_WITHIN_30_DAYS"));
    }

    // ── DELETE ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE — grille programmée → 204, annulée au nom de l'admin")
    void delete_scheduled_204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/admin/commission-grids/{id}", id).with(authentication(adminAuth())))
                .andExpect(status().isNoContent());

        verify(gridService).cancel(id, ADMIN_ID);
    }

    @Test
    @DisplayName("DELETE — grille déjà en vigueur → 409 commission-grid-already-effective")
    void delete_effective_409() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "commission-grid-already-effective",
                "Commission Grid Already Effective", "déjà en vigueur")).when(gridService).cancel(id, ADMIN_ID);

        mockMvc.perform(delete("/admin/commission-grids/{id}", id).with(authentication(adminAuth())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("commission-grid-already-effective")));
    }

    @Test
    @DisplayName("DELETE — SUPPORT → 403")
    void delete_withoutConfigManage_403() throws Exception {
        mockMvc.perform(delete("/admin/commission-grids/{id}", UUID.randomUUID()).with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET — grille en vigueur, programmée et historique paginé (annulées comprises) ; le tri du client est ignoré")
    void get_returnsCurrentScheduledHistory() throws Exception {
        UUID currentId = UUID.randomUUID();
        UUID scheduledId = UUID.randomUUID();
        UUID cancelledId = UUID.randomUUID();
        LocalDateTime cancelledAt = LocalDateTime.of(2026, 10, 20, 9, 0);
        when(gridService.current()).thenReturn(Optional.of(grid(currentId)));
        when(gridService.scheduled()).thenReturn(Optional.of(grid(scheduledId)));
        when(gridService.history(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                new CommissionGridHistoryEntry(grid(scheduledId), null, ADMIN_ID, "Programmée", T0.minusDays(3)),
                new CommissionGridHistoryEntry(grid(cancelledId), cancelledAt, ADMIN_ID, "Annulée", T0.minusDays(9))),
                PageRequest.of(1, 5), 7));

        mockMvc.perform(get("/admin/commission-grids").param("page", "1").param("size", "5").param("sort", "id,desc")
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.id").value(currentId.toString()))
                .andExpect(jsonPath("$.scheduled.id").value(scheduledId.toString()))
                .andExpect(jsonPath("$.history.content.length()").value(2))
                .andExpect(jsonPath("$.history.totalElements").value(7))
                .andExpect(jsonPath("$.history.content[0].note").value("Programmée"))
                .andExpect(jsonPath("$.history.content[0].cancelledAt").doesNotExist())
                .andExpect(jsonPath("$.history.content[1].cancelledAt").exists())
                .andExpect(jsonPath("$.history.content[1].createdBy").value(ADMIN_ID.toString()));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(gridService).history(pageable.capture());
        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(1, 5));
    }

    @Test
    @DisplayName("GET — aucune grille : current et scheduled absents, taille de page plafonnée à 100")
    void get_noGrid_andClampedPage() throws Exception {
        when(gridService.current()).thenReturn(Optional.empty());
        when(gridService.scheduled()).thenReturn(Optional.empty());
        when(gridService.history(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/admin/commission-grids").param("page", "-3").param("size", "5000")
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").doesNotExist())
                .andExpect(jsonPath("$.scheduled").doesNotExist());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(gridService).history(pageable.capture());
        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(0, 100));
    }

    @Test
    @DisplayName("GET — SUPPORT → 403")
    void get_withoutConfigManage_403() throws Exception {
        mockMvc.perform(get("/admin/commission-grids").with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    // ── Simulation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /simulate — gridId : une ligne par net, rails de la carte et des espèces")
    void simulate_byGridId_200() throws Exception {
        UUID id = UUID.randomUUID();
        when(gridService.require(id)).thenReturn(grid(id));
        when(gridService.current()).thenReturn(Optional.empty());
        when(gridService.flatPlatformGrid()).thenReturn(new CommissionGrid(null, LocalDateTime.MIN,
                List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.05"))),
                new EnumMap<>(SupportedCurrency.class) {{ for (SupportedCurrency c : SupportedCurrency.values()) put(c, BigDecimal.ZERO); }}));

        mockMvc.perform(post("/admin/commission-grids/simulate").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gridId\":\"" + id + "\",\"currency\":\"EUR\",\"nets\":[20]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].draft.commission").value(1.4))
                .andExpect(jsonPath("$.rows[0].current.commission").value(1.0))
                .andExpect(jsonPath("$.rows[0].rails[0].rail").value("CARD_STANDARD"))
                .andExpect(jsonPath("$.rows[0].rails[?(@.rail=='CASH')]").exists())
                .andExpect(jsonPath("$.warnings.length()").value(0));
    }

    @Test
    @DisplayName("POST /simulate — corps invalide (devise et montants absents) → 422 ; SUPPORT → 403")
    void simulate_invalid_422_andForbidden() throws Exception {
        mockMvc.perform(post("/admin/commission-grids/simulate").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nets\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.currency").exists())
                .andExpect(jsonPath("$.violations.nets").exists());

        mockMvc.perform(post("/admin/commission-grids/simulate").with(authentication(supportAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"EUR\",\"nets\":[20]}"))
                .andExpect(status().isForbidden());
    }
}
