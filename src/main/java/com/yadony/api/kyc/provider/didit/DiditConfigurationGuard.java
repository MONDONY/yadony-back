package com.yadony.api.kyc.provider.didit;

import com.yadony.api.config.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Signale au démarrage, et dans le health, une configuration Didit incomplète.
 *
 * <p>Le 27/09, {@code DIDIT_WORKFLOW_ID} était vide en staging : {@code application.yml} lui
 * donne un défaut vide, rien ne le signalait au déploiement, et les testeurs bêta ont buté
 * trois jours sur « erreur serveur » à chaque vérification d'identité avant que Sentry ne
 * remonte le 400 de Didit. Ce garde fait sortir la panne dès le démarrage du conteneur.
 *
 * <p>Il ne crie ERROR que si Didit est le fournisseur actif ({@code kyc_didit_enabled},
 * lu en base, d'où l'écoute d'{@link ApplicationReadyEvent} plutôt qu'un
 * {@code @PostConstruct}) en prod ou en staging. Une configuration absente pendant que Stripe
 * Identity sert les sessions n'est qu'un avertissement.
 *
 * <p>Jamais bloquant, même raison que {@code SmsOtpConfigurationGuard} : un statut DOWN ferait
 * boucler le conteneur en redémarrage, une panne totale pire que le KYC seul en panne.
 *
 * <p>Seule l'absence est détectable ici : un {@code workflow_id} présent mais inconnu de Didit
 * ne se révèle qu'au premier appel, que {@code DiditIdentityProvider} journalise en ERROR.
 */
@Component("diditKyc")
public class DiditConfigurationGuard implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DiditConfigurationGuard.class);

    private final DiditProperties properties;
    private final PlatformSettingsService settings;
    private final List<String> activeProfiles;

    public DiditConfigurationGuard(DiditProperties properties,
                                   PlatformSettingsService settings,
                                   Environment environment) {
        this.properties = properties;
        this.settings = settings;
        this.activeProfiles = Arrays.asList(environment.getActiveProfiles());
    }

    /** Noms des variables d'environnement vides, dans l'ordre où l'exploitant les pose. */
    List<String> missingVariables() {
        List<String> missing = new ArrayList<>();
        if (isBlank(properties.apiKey())) missing.add("DIDIT_API_KEY");
        if (isBlank(properties.workflowId())) missing.add("DIDIT_WORKFLOW_ID");
        if (isBlank(properties.webhookSecret())) missing.add("DIDIT_WEBHOOK_SECRET");
        return missing;
    }

    @EventListener(ApplicationReadyEvent.class)
    void reportConfigurationAtStartup() {
        List<String> missing = missingVariables();
        if (missing.isEmpty()) {
            return;
        }

        Boolean diditEnabled = readDiditEnabled();
        boolean deployed = activeProfiles.contains("prod") || activeProfiles.contains("staging");

        if (deployed && !Boolean.FALSE.equals(diditEnabled)) {
            log.error("❌ Didit est le fournisseur KYC actif mais {} vide(s) : toute création de "
                    + "session de vérification échouera en 503 et aucun utilisateur ne pourra "
                    + "vérifier son identité. Poser la ou les variables dans l'environnement de "
                    + "déploiement, puis redémarrer.", String.join(", ", missing));
            return;
        }

        log.warn("⚠️  Configuration Didit incomplète ({} vide(s), didit actif={}) : les sessions "
                + "Didit échoueront tant que le fournisseur restera Stripe Identity ou que ces "
                + "variables manqueront.", String.join(", ", missing), diditEnabled);
    }

    /** Toujours {@code UP}, même mal configuré — voir la Javadoc de la classe. */
    @Override
    public Health health() {
        return Health.up()
                .withDetail("diditEnabled", String.valueOf(readDiditEnabled()))
                .withDetail("apiKeyConfigured", !isBlank(properties.apiKey()))
                .withDetail("workflowIdConfigured", !isBlank(properties.workflowId()))
                .withDetail("webhookSecretConfigured", !isBlank(properties.webhookSecret()))
                .build();
    }

    /**
     * {@code null} si le réglage est illisible (base injoignable) : on ne sait pas, et le
     * démarrage traite ce cas comme « actif » pour ne pas taire une vraie panne.
     */
    private Boolean readDiditEnabled() {
        try {
            return settings.kycDiditEnabled();
        } catch (RuntimeException e) {
            log.warn("Réglage kyc_didit_enabled illisible ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
