package com.yadony.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Rejoue une unité de travail transactionnelle abandonnée par PostgreSQL sur un conflit de verrou
 * (interblocage détecté, délai de verrou dépassé) : {@link PessimisticLockingFailureException}, dont
 * {@code CannotAcquireLockException}. Filet seulement : l'ordre de verrous unique colis → paiement
 * (PROD_CHECKLIST 9.25) doit suffire.
 *
 * <p><b>Contrat</b> : chaque tentative ouvre SA PROPRE transaction (méthode {@code @Transactional}
 * appelée hors transaction, ou {@code REQUIRES_NEW}). Rejouer dans une transaction déjà abandonnée
 * par PostgreSQL ne servirait à rien ({@code current transaction is aborted}).
 */
public final class TransientLockRetry {

    private static final Logger log = LoggerFactory.getLogger(TransientLockRetry.class);

    /** Une tentative et deux rejeux. */
    public static final int MAX_ATTEMPTS = 3;

    private static final long BASE_BACKOFF_MS = 50;

    private TransientLockRetry() {
    }

    /**
     * @param operation   libellé pour les journaux
     * @param work        unité de travail, une transaction neuve par appel
     * @param onExhausted exception à lever après le dernier échec (reçoit le dernier conflit)
     */
    public static <T> T run(String operation, Supplier<T> work,
                            Function<PessimisticLockingFailureException, ? extends RuntimeException> onExhausted) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("{} : conflit de verrou persistant après {} tentatives ({})", operation, attempt,
                            e.getMostSpecificCause().getMessage());
                    throw onExhausted.apply(e);
                }
                log.info("{} : conflit de verrou (tentative {}/{}), rejeu dans une transaction neuve",
                        operation, attempt, MAX_ATTEMPTS);
                pause(attempt);
            }
        }
    }

    private static void pause(int attempt) {
        long millis = BASE_BACKOFF_MS * attempt + ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MS);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
