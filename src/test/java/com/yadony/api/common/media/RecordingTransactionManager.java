package com.yadony.api.common.media;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Gestionnaire de transactions sans base pour les tests unitaires des participants de purge :
 * il ouvre de vraies transactions Spring (TransactionSynchronizationManager les voit), ce qui
 * permet d'asserter qu'aucun appel R2 n'a lieu pendant qu'une connexion serait tenue.
 */
public class RecordingTransactionManager extends AbstractPlatformTransactionManager {

    private int begun;

    public int begun() { return begun; }

    /** Vrai si l'appelant s'exécute dans une transaction ouverte (même test que pour une vraie connexion). */
    public static boolean active() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        begun++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        // rien à valider
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        // rien à annuler
    }
}
