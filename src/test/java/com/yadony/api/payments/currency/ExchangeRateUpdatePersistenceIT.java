package com.yadony.api.payments.currency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verrouille la PERSISTANCE réelle d'{@link ExchangeRateUpdateService#apply} à
 * travers toute la chaîne synchrone (listener de pivot inclus).
 *
 * <p>Régression du 2026-09-02 en production : le listener du pivot exécute un bulk
 * JPQL {@code @Modifying(clearAutomatically = true)} DANS la transaction d'apply —
 * sans {@code flushAutomatically}, le clear anéantissait la modification du taux
 * encore en attente de flush. Commit vert, audit écrit, logs « synchronisé »,
 * table intacte. Ce test relit la base après un clear explicite du contexte de
 * persistance : il échoue si l'écriture du taux redevient dépendante de l'état du
 * contexte au moment du commit.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("ExchangeRateUpdatePersistenceIT — l'écriture du taux survit au listener de pivot")
class ExchangeRateUpdatePersistenceIT {

    @Autowired ExchangeRateUpdateService updateService;
    @Autowired ExchangeRateRepository exchangeRateRepository;
    @PersistenceContext EntityManager entityManager;

    @BeforeEach
    void seedRates() {
        exchangeRateRepository.deleteAll();
        exchangeRateRepository.saveAll(List.of(
                new ExchangeRateEntity("EUR", new BigDecimal("1")),
                new ExchangeRateEntity("USD", new BigDecimal("1.08"))));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("apply écrit le taux en base, pas seulement dans l'objet retourné")
    void apply_persistsRate_throughSynchronousPivotListener() {
        updateService.apply("USD", new BigDecimal("1.2345"), null, "EXCHANGE_RATE_UPDATED");

        // Vider le contexte de persistance : toute valeur encore visible doit venir
        // de la BASE (flushée), pas du cache de premier niveau de la transaction.
        entityManager.clear();

        BigDecimal persisted = exchangeRateRepository.findByCurrency("USD")
                .orElseThrow()
                .getUnitsPerEur();
        assertThat(persisted).isEqualByComparingTo("1.2345");
    }
}
