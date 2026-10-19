package com.yadony.api.common.commission;

import com.yadony.api.payments.currency.SupportedCurrency;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sur un PostgreSQL embarqué avec le vrai Flyway (V332) : les colonnes {@code tiers} et
 * {@code minimums} y sont réellement {@code jsonb}, le trigger d'immuabilité est actif, et
 * {@code ddl-auto=validate} fait échouer le contexte au moindre écart entre l'entité et la table.
 * Chaque test est transactionnel et annulé : le trigger n'interdit que ce qui est validé.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("CommissionGridRepository — jsonb, @Where, historique, trigger d'immuabilité")
class CommissionGridRepositoryIT {

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

    @Autowired CommissionGridRepository repository;
    @Autowired TestEntityManager em;

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private CommissionGridEntity persist(LocalDateTime effectiveFrom, String note) {
        CommissionGridEntity entity = new CommissionGridEntity(effectiveFrom,
                List.of(new CommissionTier(new BigDecimal("0"), new BigDecimal("0.07")),
                        new CommissionTier(new BigDecimal("100"), new BigDecimal("0.06")),
                        new CommissionTier(new BigDecimal("300"), new BigDecimal("0.05"))),
                CommissionGridService.defaultMinimums(), note, UUID.randomUUID());
        return em.persistFlushFind(entity);
    }

    @Test
    @DisplayName("tiers et minimums font l'aller-retour jsonb sans perdre une décimale")
    void jsonbRoundTrip_keepsExactDecimals() {
        CommissionGridEntity saved = persist(utcNow().plusDays(5), "note");
        em.clear();

        CommissionGrid grid = repository.findById(saved.getId()).orElseThrow().toGrid();

        assertThat(grid.id()).isEqualTo(saved.getId());
        assertThat(grid.tiers()).hasSize(3);
        assertThat(grid.tiers().get(1).fromEur()).isEqualByComparingTo("100");
        assertThat(grid.tiers().get(1).rate()).isEqualByComparingTo("0.06");
        assertThat(grid.minimums()).containsOnlyKeys(SupportedCurrency.values());
        assertThat(grid.minimum(SupportedCurrency.USD)).isEqualByComparingTo("1.10");
        assertThat(grid.minimum(SupportedCurrency.XOF)).isEqualByComparingTo("650");
    }

    @Test
    @DisplayName("findAllByOrderByEffectiveFromDesc : la plus récente d'abord")
    void findAll_ordersByEffectiveFromDesc() {
        CommissionGridEntity older = persist(utcNow().plusDays(10), null);
        CommissionGridEntity newer = persist(utcNow().plusDays(20), null);

        List<UUID> ids = repository.findAllByOrderByEffectiveFromDesc().stream().map(CommissionGridEntity::getId).toList();

        assertThat(ids.indexOf(newer.getId())).isLessThan(ids.indexOf(older.getId()));
        assertThat(ids).contains(older.getId(), newer.getId());
    }

    @Test
    @DisplayName("findByEffectiveFromAfter : seules les grilles pas encore en vigueur")
    void findByEffectiveFromAfter_returnsOnlyFutureGrids() {
        CommissionGridEntity inForce = persist(utcNow().minusDays(1), null);
        CommissionGridEntity future = persist(utcNow().plusDays(30), null);

        List<UUID> ids = repository.findByEffectiveFromAfter(utcNow()).stream().map(CommissionGridEntity::getId).toList();

        assertThat(ids).contains(future.getId()).doesNotContain(inForce.getId());
    }

    @Test
    @DisplayName("une grille annulée disparaît des lectures courantes mais reste dans l'historique")
    void cancelledGrid_hiddenByWhere_butPresentInHistory() {
        CommissionGridEntity scheduled = persist(utcNow().plusDays(30), "à annuler");
        scheduled.setDeletedAt(utcNow());
        em.flush();
        em.clear();

        assertThat(repository.findById(scheduled.getId())).isEmpty();
        assertThat(repository.findAllByOrderByEffectiveFromDesc()).extracting(CommissionGridEntity::getId)
                .doesNotContain(scheduled.getId());
        Page<CommissionGridEntity> history = repository.findHistory(PageRequest.of(0, 50));
        CommissionGridEntity inHistory = history.getContent().stream()
                .filter(e -> e.getId().equals(scheduled.getId())).findFirst().orElseThrow();
        assertThat(inHistory.getDeletedAt()).isNotNull();
        assertThat(inHistory.getNote()).isEqualTo("à annuler");
        assertThat(history.getTotalElements()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("findHistory : trié par date d'effet décroissante et paginé")
    void findHistory_isSortedAndPaged() {
        CommissionGridEntity a = persist(utcNow().plusDays(11), null);
        CommissionGridEntity b = persist(utcNow().plusDays(12), null);
        CommissionGridEntity c = persist(utcNow().plusDays(13), null);

        Page<CommissionGridEntity> firstPage = repository.findHistory(PageRequest.of(0, 2));
        Page<CommissionGridEntity> secondPage = repository.findHistory(PageRequest.of(1, 2));

        assertThat(firstPage.getContent()).extracting(CommissionGridEntity::getId).containsExactly(c.getId(), b.getId());
        assertThat(secondPage.getContent()).extracting(CommissionGridEntity::getId).first().isEqualTo(a.getId());
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("le trigger refuse l'annulation d'une grille déjà en vigueur")
    void triggerRejectsCancellingAnEffectiveGrid() {
        // Une grille déjà en vigueur ne peut être insérée que dans le passé : l'INSERT n'est pas soumis au trigger.
        CommissionGridEntity inForce = persist(utcNow().minusDays(1), null);

        inForce.setDeletedAt(utcNow());

        assertThatThrownBy(() -> em.flush()).hasStackTraceContaining("commission_grid_immutable");
    }
}
