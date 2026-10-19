package com.yadony.api.common.commission;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.CacheConfig;
import com.yadony.api.config.PlatformSettingsService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Le service câblé comme en production (constructeur public {@code @Autowired}, vrai
 * {@code CacheConfig}, vrai audit) sur PostgreSQL embarqué avec Flyway : transactions validées pour de
 * vrai (sans cela l'éviction du cache après commit ne se déclenche jamais), trigger d'immuabilité actif.
 * Les grilles en vigueur ne se suppriment pas : les tests s'isolent par leurs propres identifiants.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommissionGridService.class, CommissionGridWriteLock.class, CacheConfig.class, AuditService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("CommissionGridService — câblage réel, commit, cache et trigger")
class CommissionGridServiceIT {

    private static final UUID ADMIN = UUID.randomUUID();

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

    @MockitoBean PlatformSettingsService settings;
    @Autowired CommissionGridService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired CommissionGridRepository repository;
    @Autowired AuditService auditService;
    @Autowired CacheManager cacheManager;
    @Autowired CommissionGridWriteLock writeLock;
    @Autowired PlatformTransactionManager transactionManager;

    private static CommissionGridDraft draft(LocalDateTime effectiveFrom) {
        return new CommissionGridDraft(
                List.of(new CommissionTier(BigDecimal.ZERO, new BigDecimal("0.07")),
                        new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06"))),
                CommissionGridService.defaultMinimums(), effectiveFrom, "IT");
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("effet immédiat : la grille est la grille en vigueur dès le commit, l'audit est écrit")
    void immediateGrid_becomesCurrent_andIsAudited() {
        when(settings.commissionRate()).thenReturn(new BigDecimal("0.05"));
        CommissionGrid grid = service.schedule(draft(null), Set.of(CommissionGridWarning.INCREASE_WITHIN_30_DAYS), ADMIN);

        assertThat(service.current()).map(CommissionGrid::id).contains(grid.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'COMMISSION_GRID_SCHEDULED'",
                Integer.class, grid.id())).isEqualTo(1);
        // Une grille en vigueur ne s'annule pas : refus du service avant même le trigger.
        assertThatThrownBy(() -> service.cancel(grid.id(), ADMIN))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("programmer une seconde grille annule la première, qui reste dans l'historique")
    void secondScheduledGrid_replacesFirst_andHistoryKeepsBoth() {
        when(settings.commissionRate()).thenReturn(new BigDecimal("0.05"));
        CommissionGrid first = service.schedule(draft(utcNow().plusDays(60)), Set.of(), ADMIN);
        assertThat(service.scheduled()).map(CommissionGrid::id).contains(first.id());

        CommissionGrid second = service.schedule(draft(utcNow().plusDays(61)), Set.of(), ADMIN);

        assertThat(service.scheduled()).map(CommissionGrid::id).contains(second.id());
        assertThat(service.require(second.id()).tiers()).hasSize(2);
        List<CommissionGridHistoryEntry> history = service.history(PageRequest.of(0, 100)).getContent();
        assertThat(history).filteredOn(h -> h.grid().id().equals(first.id())).singleElement()
                .satisfies(h -> assertThat(h.cancelledAt()).isNotNull());
        assertThat(history).filteredOn(h -> h.grid().id().equals(second.id())).singleElement()
                .satisfies(h -> {
                    assertThat(h.cancelledAt()).isNull();
                    assertThat(h.createdBy()).isEqualTo(ADMIN);
                    assertThat(h.note()).isEqualTo("IT");
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'COMMISSION_GRID_CANCELLED'",
                Integer.class, first.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("annuler une grille programmée la masque aux lectures, cache compris")
    void cancelScheduledGrid_hidesItFromReads() {
        when(settings.commissionRate()).thenReturn(new BigDecimal("0.05"));
        CommissionGrid grid = service.schedule(draft(utcNow().plusDays(70)), Set.of(), ADMIN);
        assertThat(service.scheduled()).map(CommissionGrid::id).contains(grid.id());

        service.cancel(grid.id(), ADMIN);

        assertThat(service.scheduled().map(CommissionGrid::id).orElse(null)).isNotEqualTo(grid.id());
        assertThatThrownBy(() -> service.require(grid.id()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("deux programmations simultanées : une seule grille reste programmée (verrou consultatif)")
    void concurrentSchedules_leaveASingleScheduledGrid() throws Exception {
        when(settings.commissionRate()).thenReturn(new BigDecimal("0.05"));
        int writers = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<CommissionGrid>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                LocalDateTime effectiveFrom = utcNow().plusDays(90 + i);
                results.add(pool.submit(() -> {
                    start.await();
                    return service.schedule(draft(effectiveFrom), Set.of(), ADMIN);
                }));
            }
            start.countDown();
            for (Future<CommissionGrid> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Sans verrou, chaque transaction lisait « aucune grille programmée » avant le commit des autres.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commission_grids "
                + "WHERE deleted_at IS NULL AND effective_from > (now() AT TIME ZONE 'UTC')", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("annulation d'une grille devenue en vigueur avant l'écriture : 409, pas 500, rien n'est écrit")
    void cancel_gridBecomesEffectiveBeforeFlush_isConflict() {
        // La base voit la grille en vigueur depuis un jour ; l'horloge du service retarde de deux jours
        // (même effet qu'une date d'effet atteinte entre le contrôle Java et l'UPDATE) : le contrôle passe,
        // le trigger de V331 refuse. Écarts d'un jour : Hibernate (jdbc.time_zone UTC) relit une date
        // écrite en SQL décalée du fuseau de la JVM, quelques heures au plus.
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO commission_grids (id, effective_from, tiers, minimums, created_by, created_at, updated_at) "
                + "VALUES (?, (now() AT TIME ZONE 'UTC') - interval '1 day', "
                + "'[{\"fromEur\":\"0\",\"rate\":\"0.07\"}]'::jsonb, "
                + "'{\"EUR\":\"1.00\",\"XOF\":\"650\",\"XAF\":\"650\",\"USD\":\"1.10\",\"CAD\":\"1.50\",\"GBP\":\"0.90\",\"CHF\":\"0.95\"}'::jsonb, "
                + "?, now(), now())", id, ADMIN);
        Clock lagging = Clock.offset(Clock.systemUTC(), Duration.ofDays(-2));
        CommissionGridService lateService = new CommissionGridService(repository, auditService, settings,
                cacheManager, writeLock, lagging);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> lateService.cancel(id, ADMIN)))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("commission-grid-already-effective");
                });
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM commission_grids WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ?", Integer.class, id))
                .isZero();
    }
}
