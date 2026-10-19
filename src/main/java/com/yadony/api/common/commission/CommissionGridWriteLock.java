package com.yadony.api.common.commission;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sérialise les écritures des grilles de commission (programmation, annulation) entre transactions
 * et entre instances : deux programmations simultanées lisaient chacune « aucune grille programmée »
 * avant le commit de l'autre et en laissaient deux. Le verrou consultatif PostgreSQL est pris dans
 * la transaction de l'appelant et relâché à son commit ou à son annulation ; la seconde écriture
 * attend donc la première, puis relit l'état validé.
 *
 * <p>Sous H2 (profil de test) la fonction n'existe pas : le verrou n'est pris que sur PostgreSQL.
 */
@Component
public class CommissionGridWriteLock {

    /**
     * Clé du verrou consultatif des grilles ; « YADGRID » en ASCII. Unique dans l'application : aucun
     * autre {@code pg_advisory_xact_lock} ne doit la réutiliser.
     */
    static final long LOCK_KEY = 0x59414447524944L;

    private final JdbcTemplate jdbc;
    private volatile Boolean postgres;

    public CommissionGridWriteLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Prend le verrou des grilles pour la transaction en cours ; bloque tant qu'une autre le détient. */
    public void acquire() {
        if (isPostgres()) {
            jdbc.execute("SELECT pg_advisory_xact_lock(" + LOCK_KEY + ")");
        }
    }

    private boolean isPostgres() {
        Boolean known = postgres;
        if (known == null) {
            String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            known = "PostgreSQL".equalsIgnoreCase(product);
            postgres = known;
        }
        return known;
    }
}
