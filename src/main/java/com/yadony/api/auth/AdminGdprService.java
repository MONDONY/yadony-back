package com.yadony.api.auth;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Lot C — traitement administrateur des demandes de suppression RGPD.
 *
 * <p>Ce service ne réimplémente aucune anonymisation : il délègue à
 * {@link AccountFinalizationService#finalize}, point d'entrée unique et déjà fonctionnel.
 *
 * <p>Il ne passe délibérément <strong>pas</strong> par {@code AuthService.deleteImmediately} :
 * celle-ci exige un {@code auth_time} Firebase de moins de 5 minutes appartenant à
 * l'utilisateur lui-même, condition qu'un token administrateur ne satisfait jamais — le
 * geste échouerait systématiquement en {@code 401 reauth-required}.
 */
@Service
public class AdminGdprService {

    private static final Logger log = LoggerFactory.getLogger(AdminGdprService.class);

    private final UserRepository userRepository;
    private final UserService userService;
    private final AccountFinalizationService accountFinalizationService;
    private final AuditService auditService;

    public AdminGdprService(UserRepository userRepository,
                            UserService userService,
                            AccountFinalizationService accountFinalizationService,
                            AuditService auditService) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.accountFinalizationService = accountFinalizationService;
        this.auditService = auditService;
    }

    /** File des demandes en attente, les plus anciennes d'abord. */
    @Transactional(readOnly = true)
    public Page<UserEntity> listDeletionRequests(Pageable pageable) {
        return userRepository.findByDeletionRequestedAtIsNotNullOrderByDeletionRequestedAtAsc(pageable);
    }

    /**
     * Exécute immédiatement l'anonymisation d'un compte. <strong>Irréversible.</strong>
     *
     * <p>Même garde que le chemin self-service, portée par {@link UserService} : escrow actif
     * → 422 {@code active-transactions}. Un solde wallet positif n'est plus bloquant (Apple
     * 5.1.1(v)) — un ticket de remboursement est ouvert automatiquement et la finalisation se
     * poursuit.
     *
     * <p>Un compte déjà finalisé n'est plus visible ({@code @Where(deleted_at IS NULL)}) :
     * un second appel répond 404, ce qui est l'issue attendue pour un geste irréversible.
     */
    @Transactional
    public void executeDeletion(UUID userId, UUID adminId, String reason, boolean withoutUserRequest) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "Not Found", "Utilisateur introuvable"));

        // Garde le plus important de cette méthode, et le premier à s'exécuter : le geste est
        // irréversible. La file GET /gdpr-requests ne liste que les comptes portant un
        // deletionRequestedAt, mais rien ne la reliait TECHNIQUEMENT à l'exécution — l'API
        // acceptait n'importe quel identifiant. Une demande reçue par courrier ou par mail
        // reste possible : elle exige seulement un aveu explicite, consigné dans l'audit.
        if (user.getDeletionRequestedAt() == null && !withoutUserRequest) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "gdpr-no-request",
                    "Unprocessable",
                    "Cet utilisateur n'a pas demandé la suppression de son compte. Si la demande "
                            + "a été reçue hors application, confirmez-le explicitement.");
        }

        if (userService.hasActiveEscrow(user.getId())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "active-transactions",
                    "Unprocessable", "Impossible — cet utilisateur a des transactions en cours");
        }
        userService.openWalletRefundTicketIfNeeded(user.getId());

        // Écrit AVANT finalize() : celui-ci journalise USER_GDPR_DELETION avec l'utilisateur
        // comme acteur. Sans cette entrée, l'administrateur à l'origine du geste ne serait
        // tracé nulle part — audit_log étant immuable, la trace ne pourrait plus être ajoutée.
        // withoutUserRequest est consigné : sans lui, on ne pourrait plus distinguer après coup
        // une exécution régulière d'une exécution décidée hors application.
        auditService.log("USER", user.getId(), "USER_GDPR_EXECUTED", adminId,
                Map.of("initiatedBy", "admin",
                        "reason", reason != null ? reason : "",
                        "withoutUserRequest", String.valueOf(withoutUserRequest)));

        accountFinalizationService.finalize(user, FinalizationReason.ADMIN_INITIATED);
        log.info("GDPR deletion executed for user {} by admin {}", userId, adminId);
    }
}
