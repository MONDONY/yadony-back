package com.yadony.api.payments.supplement;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Somme des nets de suppléments par statut ({@code BidExtrasNet}), sur un PostgreSQL embarqué avec
 * le vrai Flyway : la requête JPQL est validée au démarrage et l'agrégat rend bien 0 sans ligne.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("BidSupplementRepository.sumNetByBidIdAndStatusIn")
class BidSupplementSumNetIT {

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

    @Autowired BidSupplementRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("ne somme que les suppléments payés et non remboursés du colis demandé")
    void sumsOnlyPaidStatesOfTheBid() {
        // Les clés étrangères (colis, utilisateurs) ne sont pas l'objet du test : on les suspend (transaction annulée).
        jdbc.execute("SET LOCAL session_replication_role = replica");
        UUID bid = UUID.randomUUID();
        UUID otherBid = UUID.randomUUID();
        insert(bid, "PAID_OUT", "20.00");
        insert(bid, "REFUNDED", "5.00");
        insert(bid, "DECLINED", "3.00");
        insert(otherBid, "PAID", "7.00");

        assertThat(repository.sumNetByBidIdAndStatusIn(bid, BidSupplementStatus.PAID_STATES))
                .isEqualByComparingTo("20.00");
        assertThat(repository.sumNetByBidIdAndStatusIn(otherBid, BidSupplementStatus.PAID_STATES))
                .isEqualByComparingTo("7.00");
    }

    @Test
    @DisplayName("rend 0 (jamais null) quand le colis n'a aucun supplément payé")
    void noPaidSupplement_isZero() {
        assertThat(repository.sumNetByBidIdAndStatusIn(UUID.randomUUID(), BidSupplementStatus.PAID_STATES))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private void insert(UUID bidId, String status, String net) {
        jdbc.update("""
                INSERT INTO bid_supplements (id, bid_id, traveler_id, sender_id, rail, status, currency,
                    net_amount, commission_amount, gross_amount, reason_code, photo_key, expires_at)
                VALUES (?, ?, ?, ?, 'PAWAPAY', ?, 'EUR', ?::numeric, 0, ?::numeric, 'OTHER', 'k', now())
                """, UUID.randomUUID(), bidId, UUID.randomUUID(), UUID.randomUUID(), status, net, net);
    }
}
