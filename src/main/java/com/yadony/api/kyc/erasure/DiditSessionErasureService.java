package com.yadony.api.kyc.erasure;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.kyc.provider.didit.DiditClient;
import com.yadony.api.kyc.provider.didit.DiditClient.DeletionOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Effacement chez Didit des sessions de vérification d'un compte supprimé (RGPD).
 *
 * <ul>
 *   <li>{@link #enqueue} écrit une ligne par session dans la transaction de la finalisation :
 *       rien n'est perdu si le serveur s'arrête ou si Didit ne répond pas.</li>
 *   <li>{@link #attempt} traite une ligne : bail pris dans une transaction courte, appel Didit
 *       hors transaction, résultat écrit dans une autre transaction courte. Jamais d'appel réseau
 *       dans une transaction.</li>
 *   <li>Un échec repousse l'essai (délai doublé à chaque fois, plafonné à un jour). Après le
 *       dernier essai, la ligne passe {@code FAILED} : alerte admin et entrée d'audit, la session
 *       reste à effacer depuis la console Didit.</li>
 * </ul>
 *
 * <p>Un échec ne bloque jamais la finalisation du compte. Le contenu des réponses Didit n'est
 * jamais lu ni journalisé.
 */
@Service
public class DiditSessionErasureService {

    private static final Logger log = LoggerFactory.getLogger(DiditSessionErasureService.class);

    static final String ALERT_CODE = "DIDIT_SESSION_DELETE_FAILED";
    static final Duration LEASE = Duration.ofMinutes(10);
    static final Duration FIRST_RETRY_DELAY = Duration.ofMinutes(15);
    static final Duration MAX_RETRY_DELAY = Duration.ofDays(1);

    private final DiditSessionDeletionRepository repository;
    private final DiditClient diditClient;
    private final AuditService auditService;
    private final AdminAlertService adminAlertService;
    private final TransactionTemplate stepTransaction;
    private final int maxAttempts;
    private final int batchSize;

    public DiditSessionErasureService(DiditSessionDeletionRepository repository,
                                      DiditClient diditClient,
                                      AuditService auditService,
                                      AdminAlertService adminAlertService,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${yadony.kyc.didit-erasure.max-attempts:8}") int maxAttempts,
                                      @Value("${yadony.kyc.didit-erasure.batch-size:20}") int batchSize) {
        this.repository = repository;
        this.diditClient = diditClient;
        this.auditService = auditService;
        this.adminAlertService = adminAlertService;
        this.stepTransaction = new TransactionTemplate(transactionManager);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.batchSize = Math.max(1, batchSize);
    }

    /** Idempotent : une session déjà en file n'est pas réinscrite. Rend le nombre de lignes créées. */
    @Transactional
    public int enqueue(UUID userId, Collection<String> sessionIds) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String sessionId : sessionIds) {
            if (sessionId != null && !sessionId.isBlank()) {
                distinct.add(sessionId);
            }
        }
        LocalDateTime now = now();
        int created = 0;
        for (String sessionId : distinct) {
            if (!repository.existsBySessionId(sessionId)) {
                repository.save(new DiditSessionDeletionEntity(userId, sessionId, now));
                created++;
            }
        }
        return created;
    }

    /** Essai immédiat des sessions en attente d'un utilisateur (après commit de sa finalisation). */
    public void attemptPendingFor(UUID userId) {
        attemptAll(repository.findPendingIdsByUserId(userId));
    }

    /** Reprise des lignes dues (planificateur). Rend le nombre de lignes effectivement essayées. */
    public int attemptDue() {
        return attemptAll(repository.findDueIds(now(), PageRequest.of(0, batchSize)));
    }

    private int attemptAll(List<UUID> ids) {
        int attempted = 0;
        for (UUID id : ids) {
            try {
                if (attempt(id)) {
                    attempted++;
                }
            } catch (RuntimeException e) {
                log.error("Effacement Didit de la ligne {} en échec", id, e);
            }
        }
        return attempted;
    }

    /** @return {@code false} si la ligne n'était pas due ou qu'un autre essai la tenait */
    boolean attempt(UUID id) {
        DiditSessionDeletionEntity row = stepTransaction.execute(status -> {
            LocalDateTime now = now();
            if (repository.claim(id, now, now.plus(LEASE)) == 0) {
                return null;
            }
            return repository.findById(id).orElse(null);
        });
        if (row == null) {
            return false;
        }
        DeletionOutcome outcome = diditClient.deleteSession(row.getSessionId(),
                "user-deletion-" + row.getUserId());
        stepTransaction.executeWithoutResult(status -> record(id, outcome));
        return true;
    }

    private void record(UUID id, DeletionOutcome outcome) {
        DiditSessionDeletionEntity row = repository.findById(id).orElse(null);
        if (row == null || row.getStatus() != DiditSessionDeletionEntity.Status.PENDING) {
            return;
        }
        LocalDateTime now = now();
        row.setLastHttpStatus(outcome.httpStatus());
        if (outcome.erased()) {
            row.setStatus(DiditSessionDeletionEntity.Status.DONE);
            row.setCompletedAt(now);
            repository.save(row);
            auditService.log("USER", row.getUserId(), "KYC_PROVIDER_SESSION_ERASED", null,
                    payload(row, outcome));
            log.info("Session Didit {} effacée (user {}, {})", row.getSessionId(), row.getUserId(),
                    outcome.status());
            return;
        }

        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        if (attempts >= maxAttempts) {
            row.setStatus(DiditSessionDeletionEntity.Status.FAILED);
            repository.save(row);
            auditService.log("USER", row.getUserId(), "KYC_PROVIDER_SESSION_ERASURE_FAILED", null,
                    payload(row, outcome));
            adminAlertService.raise(ALERT_CODE,
                    "Compte supprimé mais session Didit non effacée après " + attempts + " essais : "
                            + "pièces d'identité encore chez Didit. L'effacer depuis la console Didit.",
                    alertContext(row, outcome));
            return;
        }
        row.setNextAttemptAt(now.plus(retryDelay(attempts)));
        repository.save(row);
        log.warn("Effacement Didit de la session {} reporté (essai {}/{}, HTTP {})", row.getSessionId(),
                attempts, maxAttempts, outcome.httpStatus());
        if (attempts == 1 && Integer.valueOf(403).equals(outcome.httpStatus())) {
            // 403 = clé API sans la permission delete:sessions : aucun nouvel essai ne passera tant
            // que la console Didit n'est pas corrigée. On prévient dès le premier refus.
            adminAlertService.raise(ALERT_CODE,
                    "Didit refuse l'effacement (403) : la clé API doit porter la permission "
                            + "delete:sessions (console Didit). Nouvel essai automatique ensuite.",
                    alertContext(row, outcome));
        }
    }

    /** 15 min, 30 min, 1 h, 2 h... plafonné à un jour. */
    static Duration retryDelay(int attempts) {
        int doublings = Math.min(Math.max(0, attempts - 1), 16);
        Duration delay = FIRST_RETRY_DELAY.multipliedBy(1L << doublings);
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private static Map<String, Object> payload(DiditSessionDeletionEntity row, DeletionOutcome outcome) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("provider", "DIDIT");
        payload.put("sessionId", row.getSessionId());
        payload.put("outcome", outcome.status().name());
        payload.put("attempts", row.getAttempts());
        if (outcome.httpStatus() != null) {
            payload.put("httpStatus", outcome.httpStatus());
        }
        return payload;
    }

    private static Map<String, Object> alertContext(DiditSessionDeletionEntity row, DeletionOutcome outcome) {
        Map<String, Object> context = new HashMap<>();
        context.put("userId", row.getUserId());
        context.put("sessionId", row.getSessionId());
        context.put("attempts", row.getAttempts());
        context.put("httpStatus", outcome.httpStatus() == null ? "aucune réponse" : outcome.httpStatus());
        return context;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
