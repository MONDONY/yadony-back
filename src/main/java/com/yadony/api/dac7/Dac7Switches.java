package com.yadony.api.dac7;

/**
 * Interrupteurs DAC7, pilotés depuis le back-office (réglages plateforme {@code dac7_enabled} et
 * {@code dac7_reminders_enabled}) et relus à chaque usage : les changer ne demande aucun redéploiement.
 *
 * <p>Éteints par défaut. Les messages exigent la collecte : {@link #remindersEnabled()} vaut vrai seulement si les
 * deux sont allumés.
 */
public interface Dac7Switches {

    /** Collecte ouverte : écran « Informations fiscales » et ses endpoints, pages admin, calcul, export. */
    boolean collectionEnabled();

    /** Messages ouverts (invite à l'acceptation, rappels) : exige aussi la collecte. */
    boolean remindersEnabled();

    /** Valeurs fixes, pour les tests sans Spring. */
    static Dac7Switches fixed(boolean collection, boolean reminders) {
        return new Dac7Switches() {
            @Override
            public boolean collectionEnabled() {
                return collection;
            }

            @Override
            public boolean remindersEnabled() {
                return collection && reminders;
            }
        };
    }
}
