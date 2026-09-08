package com.yadony.api.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.stripe.AdminAlertService;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Alerte administrateur dédupliquée par incident : une ligne {@code admin_alerts} non résolue du
 * même {@code type} bloque toute nouvelle levée. Le {@code type} porte donc l'identifiant de
 * l'incident (paiement, bid, opération, devise) — jamais le contenu du payload JSON, que
 * PostgreSQL reformate à la relecture ({@code jsonb}) et qui ne matcherait plus jamais.
 *
 * <p>La recherche et l'insertion de la ligne de dédup s'exécutent dans une transaction
 * INDÉPENDANTE ({@code REQUIRES_NEW}), commitée avant que cette méthode ne rende la main :
 * une alerte levée juste avant un {@code throw} (transaction appelante annulée) reste
 * dédupliquée — sinon la ligne serait créée puis effacée par le rollback à chaque appel, et le
 * mécanisme paraîtrait présent tout en ne servant jamais. L'envoi (journal, Sentry, Telegram)
 * a lieu APRÈS ce commit, jamais sous une connexion de base tenue pendant un appel HTTP.
 *
 * <p>{@code admin_alerts.type} est {@code VARCHAR(60)} : un préfixe suffixé par un UUID (36)
 * tient en 24 caractères. Un type trop long est refusé ici, bruyamment — l'INSERT lèverait
 * sinon une {@code DataIntegrityViolationException} avalée par le {@code catch} de boucle des
 * schedulers appelants, et l'alerte ne partirait jamais, sans autre trace qu'une ligne de log.
 */
@Component
public class AdminAlertEscalator {

    /** Longueur de la colonne {@code admin_alerts.type} (migration V20). */
    public static final int TYPE_MAX_LENGTH = 60;

    private final AdminAlertRepository repository;
    private final AdminAlertService alerts;
    private final ObjectMapper mapper;
    private final TransactionTemplate independentTransaction;

    public AdminAlertEscalator(AdminAlertRepository repository, AdminAlertService alerts, ObjectMapper mapper,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.alerts = alerts;
        this.mapper = mapper;
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Lève l'alerte {@code type} si aucune alerte non résolue du même type n'existe.
     *
     * @param context clés/valeurs de l'incident, envoyées avec l'alerte et persistées telles
     *                quelles en JSON dans {@code admin_alerts.payload}
     * @return {@code true} si l'alerte a été levée, {@code false} si une alerte non résolue du
     *         même type existait déjà
     * @throws IllegalArgumentException si {@code type} dépasse {@link #TYPE_MAX_LENGTH}
     */
    public boolean raiseOnce(String type, String detail, Map<String, Object> context) {
        if (type.length() > TYPE_MAX_LENGTH) {
            throw new IllegalArgumentException("Type d'alerte trop long pour admin_alerts.type (" + type.length()
                    + " > " + TYPE_MAX_LENGTH + ") : " + type);
        }
        Boolean created = independentTransaction.execute(status -> {
            if (!repository.findByTypeAndResolved(type, false).isEmpty()) {
                return false;
            }
            AdminAlertEntity alert = new AdminAlertEntity();
            alert.setType(type);
            alert.setPayload(toJson(context));
            alert.setResolved(false);
            repository.save(alert);
            return true;
        });
        if (!Boolean.TRUE.equals(created)) {
            return false;
        }
        alerts.raise(type, detail, context);
        return true;
    }

    private String toJson(Map<String, Object> context) {
        try {
            return mapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Contexte d'alerte non sérialisable en JSON", e);
        }
    }
}
