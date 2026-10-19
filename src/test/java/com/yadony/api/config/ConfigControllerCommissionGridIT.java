package com.yadony.api.config;

import com.google.firebase.auth.FirebaseAuth;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridDraft;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionGridWarning;
import com.yadony.api.common.commission.CommissionTier;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API publique de la grille de commission (spec 2026-10-18 §5.3-5.4). Lue par l'app et par dony-pro sans
 * jeton ; {@code /config/commission-rate} reste en place pour les anciennes versions de l'app.
 *
 * <p>PostgreSQL embarqué avec Flyway : les colonnes {@code jsonb} de {@code commission_grids} ne se lisent pas
 * sous H2. Le trigger d'immuabilité de V331 interdit de supprimer une grille en vigueur ; chaque test repart
 * pourtant d'une table vide, donc la purge le désactive le temps du {@code DELETE}.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
class ConfigControllerCommissionGridIT {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private CommissionGridService grids;
    @Autowired private PlatformSettingsService settings;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CacheManager cacheManager;
    @MockBean private FirebaseAuth firebaseAuth;

    @BeforeEach
    @AfterEach
    void purge() {
        jdbc.execute("ALTER TABLE commission_grids DISABLE TRIGGER trg_commission_grids_immutable");
        jdbc.update("DELETE FROM commission_grids");
        jdbc.execute("ALTER TABLE commission_grids ENABLE TRIGGER trg_commission_grids_immutable");
        cacheManager.getCache("commission-grids").clear();
    }

    private static CommissionGridDraft draft(LocalDateTime effectiveFrom, String firstRate) {
        return new CommissionGridDraft(
                List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal(firstRate)),
                        new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06"))),
                CommissionGridService.defaultMinimums(), effectiveFrom, "IT");
    }

    private CommissionGrid schedule(LocalDateTime effectiveFrom, String firstRate) {
        return grids.schedule(draft(effectiveFrom, firstRate),
                Set.of(CommissionGridWarning.INCREASE_WITHIN_30_DAYS), UUID.randomUUID());
    }

    @Test
    void commissionGrid_noGrid_currentNull() throws Exception {
        mockMvc.perform(get("/config/commission-grid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(nullValue()))
                .andExpect(jsonPath("$.next").value(nullValue()))
                // Les clés restent présentes : l'app distingue « pas de grille » d'une réponse mal formée.
                .andExpect(content().string("{\"current\":null,\"next\":null}"));
    }

    @Test
    void commissionGrid_currentAndScheduled() throws Exception {
        CommissionGrid current = schedule(null, "0.07");
        LocalDateTime nextFrom = LocalDateTime.now(ZoneOffset.UTC).plusDays(45).withNano(0);
        CommissionGrid next = schedule(nextFrom, "0.08");

        mockMvc.perform(get("/config/commission-grid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.gridId").value(current.id().toString()))
                .andExpect(jsonPath("$.current.effectiveFrom").isNotEmpty())
                .andExpect(jsonPath("$.current.tiers.length()").value(2))
                .andExpect(jsonPath("$.current.tiers[0].fromEur").value(0))
                .andExpect(jsonPath("$.current.tiers[0].rate").value(0.07))
                .andExpect(jsonPath("$.current.tiers[1].fromEur").value(100))
                .andExpect(jsonPath("$.current.tiers[1].rate").value(0.06))
                .andExpect(jsonPath("$.current.minimums.EUR").value(1.00))
                .andExpect(jsonPath("$.current.minimums.XOF").value(650))
                .andExpect(jsonPath("$.current.minimums.XAF").value(650))
                .andExpect(jsonPath("$.current.minimums.USD").value(1.10))
                .andExpect(jsonPath("$.current.minimums.CAD").value(1.50))
                .andExpect(jsonPath("$.current.minimums.GBP").value(0.90))
                .andExpect(jsonPath("$.current.minimums.CHF").value(0.95))
                .andExpect(jsonPath("$.next.gridId").value(next.id().toString()))
                .andExpect(jsonPath("$.next.effectiveFrom").value(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(nextFrom) + "Z"))
                .andExpect(jsonPath("$.next.tiers[0].rate").value(0.08));
    }

    @Test
    void commissionGrid_onlyScheduled_currentNullNextSet() throws Exception {
        CommissionGrid next = schedule(LocalDateTime.now(ZoneOffset.UTC).plusDays(45), "0.08");

        mockMvc.perform(get("/config/commission-grid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(nullValue()))
                .andExpect(jsonPath("$.next.gridId").value(next.id().toString()));
    }

    @Test
    void commissionGrid_isPublic_noToken200() throws Exception {
        schedule(null, "0.07");

        // Aucun en-tête Authorization : la route appartient à /config/**, publique.
        mockMvc.perform(get("/config/commission-grid"))
                .andExpect(status().isOk());
    }

    @Test
    void commissionRate_withGrid_returnsFirstTierRate() throws Exception {
        schedule(null, "0.07");

        mockMvc.perform(get("/config/commission-rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(0.07));
    }

    @Test
    void commissionRate_onlyScheduledGrid_keepsPlatformRate() throws Exception {
        schedule(LocalDateTime.now(ZoneOffset.UTC).plusDays(45), "0.08");

        mockMvc.perform(get("/config/commission-rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(settings.commissionRate().doubleValue()));
    }

    @Test
    void commissionGridById_pastGrid_isStillReadable_withoutToken() throws Exception {
        CommissionGrid past = schedule(null, "0.07");
        CommissionGrid current = schedule(null, "0.065");

        // Une grille remplacée reste lisible : un bid ou un fil qui l'a figée la relit telle quelle.
        mockMvc.perform(get("/config/commission-grid/{id}", past.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gridId").value(past.id().toString()))
                .andExpect(jsonPath("$.effectiveFrom").isNotEmpty())
                .andExpect(jsonPath("$.tiers.length()").value(2))
                .andExpect(jsonPath("$.tiers[0].rate").value(0.07))
                .andExpect(jsonPath("$.minimums.EUR").value(1.00))
                .andExpect(jsonPath("$.minimums.XOF").value(650));
        mockMvc.perform(get("/config/commission-grid/{id}", current.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiers[0].rate").value(0.065));
    }

    @Test
    void commissionGridById_unknown_404ProblemDetail() throws Exception {
        mockMvc.perform(get("/config/commission-grid/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("commission-grid-not-found"));
    }

    @Test
    void commissionGridById_cancelled_404() throws Exception {
        CommissionGrid cancelled = schedule(LocalDateTime.now(ZoneOffset.UTC).plusDays(45), "0.08");
        grids.cancel(cancelled.id(), UUID.randomUUID());

        mockMvc.perform(get("/config/commission-grid/{id}", cancelled.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("commission-grid-not-found"));
    }
}
