package com.yadony.api.auth;

import com.yadony.api.auth.dto.DeletionEligibilityResponse;
import com.yadony.api.auth.dto.UpgradeToProRequest;
import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import com.yadony.api.auth.events.UserSuspendedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.payments.wallet.WalletRefundRequestService;
import com.yadony.api.payments.wallet.WalletSelfRefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final int SUSPENSION_REFUSED_THRESHOLD = 2;
    // Coupure « indéfinie » : une échéance très lointaine plutôt qu'un null/cas
    // particulier, pour que la règle Firestore n'ait qu'une seule comparaison
    // (messagingMutedUntil > request.time) à faire, mute temporaire ou pas.
    private static final long INDEFINITE_MUTE_DAYS = 36500L;

    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final WalletAccountRepository walletAccountRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final AccountFinalizationService accountFinalizationService;
    private final FirestoreService firestoreService;
    private final NotificationDispatcher notificationDispatcher;
    private final WalletRefundRequestService walletRefundRequestService;
    private final WalletSelfRefundService walletSelfRefundService;

    public UserService(UserRepository userRepository,
                       PaymentRepository paymentRepository,
                       WalletAccountRepository walletAccountRepository,
                       AuditService auditService,
                       ApplicationEventPublisher eventPublisher,
                       AccountFinalizationService accountFinalizationService,
                       FirestoreService firestoreService,
                       NotificationDispatcher notificationDispatcher,
                       WalletRefundRequestService walletRefundRequestService,
                       WalletSelfRefundService walletSelfRefundService) {
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.walletAccountRepository = walletAccountRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.accountFinalizationService = accountFinalizationService;
        this.firestoreService = firestoreService;
        this.notificationDispatcher = notificationDispatcher;
        this.walletRefundRequestService = walletRefundRequestService;
        this.walletSelfRefundService = walletSelfRefundService;
    }

    /** Un solde wallet réel (rechargé par carte, cf. WalletTopupOrchestrator) non dépensé
     *  deviendrait orphelin une fois le compte Firebase supprimé. Purement informatif : ne
     *  bloque plus aucune suppression (cf. {@link #openWalletRefundTicketIfNeeded}). */
    public boolean hasWalletBalance(UUID userId) {
        // Un utilisateur a un portefeuille par devise : n'interroger que l'EUR
        // laisserait un solde XOF/USD devenir orphelin à la suppression.
        return walletAccountRepository.findAllByUserId(userId).stream()
                .anyMatch(w -> w.getBalance().compareTo(BigDecimal.ZERO) > 0);
    }

    /** Apple 5.1.1(v) impose que la suppression de compte reste toujours possible en
     *  self-service. Essaie d'abord le remboursement Stripe automatique sur chaque
     *  devise positive, puis retombe sur le ticket manuel admin pour les soldes
     *  entamés ou mélangés. La suppression se poursuit dans tous les cas. */
    public void openWalletRefundTicketIfNeeded(UUID userId) {
        List<com.yadony.api.payments.wallet.WalletAccountEntity> positiveBalances =
                walletAccountRepository.findAllByUserId(userId).stream()
                        .filter(w -> w.getBalance().compareTo(BigDecimal.ZERO) > 0)
                        .toList();
        if (positiveBalances.isEmpty()) {
            return;
        }

        Set<String> handledAutomatically = new HashSet<>();
        for (com.yadony.api.payments.wallet.WalletAccountEntity wallet : positiveBalances) {
            if (walletSelfRefundService.isEligible(userId, wallet.getCurrency())) {
                walletSelfRefundService.request(userId, wallet.getCurrency());
                handledAutomatically.add(wallet.getCurrency());
            }
        }

        boolean anyIneligible = positiveBalances.stream()
                .anyMatch(w -> !handledAutomatically.contains(w.getCurrency()));
        if (anyIneligible) {
            walletRefundRequestService.request(userId);
        }
    }

    /** Point unique d'accès à {@link PaymentRepository#hasActiveEscrowForUser} pour la règle de
     *  suppression — évite que {@code AuthService} dépende directement de {@code PaymentRepository}. */
    public boolean hasActiveEscrow(UUID userId) {
        return paymentRepository.hasActiveEscrowForUser(userId);
    }

    /** Story 9.8 — Vérification en lecture seule (aucune écriture, aucune exception) de
     *  l'éligibilité à la suppression de compte, pour permettre au front d'expliquer un
     *  blocage réel *avant* que l'utilisateur ne tente l'action. Seul l'escrow actif bloque
     *  encore {@code canDelete} (temporaire, se résout de lui-même) — un solde wallet ne
     *  bloque plus rien (cf. {@link #openWalletRefundTicketIfNeeded}), il est juste signalé
     *  via {@code hasWalletBalance} pour informer l'utilisateur. */
    @Transactional(readOnly = true)
    public DeletionEligibilityResponse checkDeletionEligibility(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        if (hasActiveEscrow(user.getId())) {
            return new DeletionEligibilityResponse(false, "active-transactions", false);
        }
        return new DeletionEligibilityResponse(true, null, hasWalletBalance(user.getId()));
    }

    // Story 9.5 — Suspension automatique après trop de refus de colis
    @Transactional
    public void checkAndSuspendSender(UUID senderId) {
        userRepository.findById(senderId).ifPresent(sender -> {
            if (sender.getStatus() == UserStatus.SUSPENDED || sender.getStatus() == UserStatus.BANNED) {
                return;
            }
            if (sender.getRefusedCount() >= SUSPENSION_REFUSED_THRESHOLD) {
                sender.setStatus(UserStatus.SUSPENDED);
                userRepository.save(sender);

                auditService.log("USER", senderId, "USER_AUTO_SUSPENDED", senderId,
                        Map.of("reason", "refused_count_threshold",
                                "refusedCount", sender.getRefusedCount()));

                eventPublisher.publishEvent(new UserSuspendedEvent(
                        senderId,
                        "Suspension automatique suite à " + sender.getRefusedCount() + " colis refusés"
                ));

                log.warn("Sender {} auto-suspended after {} parcel refusals", senderId, sender.getRefusedCount());
            }
        });
    }

    // Story 9.8 — Droit à l'effacement RGPD — demande initiale (période de grâce 30 jours)
    @Transactional
    public void requestDeletion(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        if (user.getStatus() == UserStatus.PENDING_DELETION) {
            return;
        }

        if (hasActiveEscrow(user.getId())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "active-transactions",
                    "Unprocessable", "Impossible — vous avez des transactions en cours");
        }
        openWalletRefundTicketIfNeeded(user.getId());

        user.setStatus(UserStatus.PENDING_DELETION);
        user.setDeletionRequestedAt(Instant.now());
        userRepository.save(user);

        eventPublisher.publishEvent(new AccountDeletionRequestedEvent(user.getId()));
        log.info("Account deletion requested for user {}", user.getId());
    }

    // Story 9.8 — Annulation de la demande de suppression (dans les 30 jours)
    @Transactional
    public void reactivateAccount(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        if (user.getStatus() != UserStatus.PENDING_DELETION) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "not-pending-deletion",
                    "Conflict", "Ce compte n'est pas en cours de suppression");
        }

        user.setStatus(UserStatus.ACTIVE);
        user.setDeletionRequestedAt(null);
        userRepository.save(user);

        auditService.log("USER", user.getId(), "USER_DELETION_CANCELLED", user.getId(), Map.of());
        log.info("Account deletion cancelled for user {}", user.getId());
    }

    // Admin — override du taux de commission Yadony d'un utilisateur (null = retour au taux global).
    @Transactional
    public UserEntity setCommissionRateOverride(UUID userId, java.math.BigDecimal rate) {
        if (rate != null && (rate.signum() < 0 || rate.compareTo(java.math.BigDecimal.ONE) >= 0)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-commission-rate",
                    "Invalid Commission Rate", "Le taux doit être dans [0, 1[ ou null (taux global)");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found",
                        "Not Found", "Utilisateur introuvable"));
        user.setCommissionRateOverride(rate);
        userRepository.save(user);
        auditService.log("USER", user.getId(), "USER_COMMISSION_RATE_OVERRIDE_SET", user.getId(),
                Map.of("rate", rate == null ? "global" : rate.toPlainString()));
        return user;
    }

    // Story 9.8 — Finalisation RGPD à J+30 (appelé par le scheduler)
    @Transactional
    public void finalizeGdprDeletion(UserEntity user) {
        accountFinalizationService.finalize(user, FinalizationReason.SOFT_GRACE_EXPIRED);
    }

    // Story 9.8 — Méthode conservée pour compatibilité avec AuthService (délègue à requestDeletion)
    @Transactional
    public void deleteAccount(String firebaseUid) {
        requestDeletion(firebaseUid);
    }

    // PR-1 — Upgrade to PRO account
    @Transactional
    public UserEntity upgradeToPro(UserEntity user, UpgradeToProRequest request) {
        UUID userId = user.getId();

        if (request.siret() != null && !request.siret().isBlank()) {
            if (!request.siret().matches("\\d{14}")) {
                throw new YadonyBusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-siret",
                        "Invalid SIRET",
                        "Le numéro SIRET doit contenir exactement 14 chiffres"
                );
            }
        }

        boolean alreadyPro = user.isProAccount();
        String auditAction = alreadyPro ? "USER_PRO_PROFILE_UPDATED" : "USER_UPGRADED_TO_PRO";

        user.setProAccount(true);
        user.setProCompanyName(request.companyName());
        user.setProSiret(request.siret());
        UserEntity saved = userRepository.save(user);

        auditService.log("USER", userId, auditAction, userId,
                Map.of("companyName", request.companyName() != null ? request.companyName() : "",
                        "siret", request.siret() != null ? request.siret() : ""));

        if (!alreadyPro) {
            eventPublisher.publishEvent(new UserProStatusChangedEvent(userId, true));
            log.info("User {} upgraded to PRO account", userId);
        } else {
            log.info("User {} PRO profile updated (companyName, siret)", userId);
        }
        return saved;
    }

    @Transactional
    public UserEntity downgradePro(UserEntity user) {
        UUID userId = user.getId();
        user.setProAccount(false);
        user.setProCompanyName(null);
        user.setProSiret(null);
        UserEntity saved = userRepository.save(user);
        auditService.log("USER", userId, "USER_DOWNGRADED_FROM_PRO", userId, Map.of());
        eventPublisher.publishEvent(new UserProStatusChangedEvent(userId, false));
        log.info("User {} downgraded from PRO account", userId);
        return saved;
    }

    @Transactional
    public UserEntity suspendUser(UUID userId, String reason, UUID adminId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));
        user.setStatus(UserStatus.SUSPENDED);
        UserEntity saved = userRepository.save(user);
        auditService.log("USER", userId, "USER_SUSPENDED_BY_ADMIN", adminId,
                Map.of("reason", reason != null ? reason : ""));
        eventPublisher.publishEvent(new UserSuspendedEvent(userId, reason));
        log.info("User {} suspended by admin", userId);
        return saved;
    }

    @Transactional
    public UserEntity banUser(UUID userId, String reason, UUID adminId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));
        user.setStatus(UserStatus.BANNED);
        UserEntity saved = userRepository.save(user);
        auditService.log("USER", userId, "USER_BANNED_BY_ADMIN", adminId,
                Map.of("reason", reason != null ? reason : ""));
        log.info("User {} banned by admin", userId);
        return saved;
    }

    // Story 9.5 — Admin unsuspend
    @Transactional
    public UserEntity unsuspendUser(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        user.setStatus(UserStatus.ACTIVE);
        UserEntity saved = userRepository.save(user);

        auditService.log("USER", userId, "USER_UNSUSPENDED", userId, Map.of());
        log.info("User {} unsuspended by admin", userId);
        return saved;
    }

    /**
     * Suspend la publication de trajets d'un voyageur (D4) — décidé par l'admin,
     * typiquement après un délai de retour de colis dépassé. N'impacte pas le login.
     */
    @Transactional
    public void suspendPublishing(UUID userId, String reason) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        user.setPublishingSuspended(true);
        user.setPublishingSuspendedAt(java.time.Instant.now());
        user.setPublishingSuspendedReason(reason);
        userRepository.save(user);

        auditService.log("USER", userId, "TRAVELER_PUBLISHING_SUSPENDED", userId,
                Map.of("reason", reason != null ? reason : ""));
        log.info("User {} publishing suspended by admin", userId);
    }

    /** Lève la suspension de publication (D4). */
    @Transactional
    public void liftPublishingSuspension(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        user.setPublishingSuspended(false);
        user.setPublishingSuspendedAt(null);
        user.setPublishingSuspendedReason(null);
        userRepository.save(user);

        auditService.log("USER", userId, "TRAVELER_PUBLISHING_SUSPENSION_LIFTED", userId, Map.of());
        log.info("User {} publishing suspension lifted by admin", userId);
    }

    /**
     * Lot B — Coupure de messagerie décidée par l'admin. La base PostgreSQL reste la
     * source de vérité ; l'état est aussi publié dans Firestore
     * ({@code moderation/{firebaseUid}}), seul point d'application réel côté client
     * (règle de sécurité Firestore).
     *
     * @param durationHours {@code null} = coupure indéfinie jusqu'à levée manuelle
     *                      (matérialisée par une échéance à +100 ans, cf.
     *                      {@link #INDEFINITE_MUTE_DAYS}).
     */
    @Transactional
    public UserEntity muteMessaging(UUID userId, Integer durationHours, String reason, UUID adminId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        Instant until = durationHours != null
                ? Instant.now().plusSeconds(durationHours * 3600L)
                : Instant.now().plus(INDEFINITE_MUTE_DAYS, ChronoUnit.DAYS);

        user.setMessagingMutedUntil(until);
        UserEntity saved = userRepository.save(user);

        auditService.log("USER", userId, "USER_MESSAGING_MUTED", adminId,
                Map.of("reason", reason != null ? reason : "", "until", until.toString()));

        notificationDispatcher.notifyUser(userId, "Messagerie suspendue",
                "Votre accès à la messagerie a été suspendu par un administrateur.",
                Map.of("type", "MESSAGING_MUTED"));

        // En dernier, délibérément : un échec de l'audit ou de la notification annulerait la
        // transaction, mais l'écriture Firestore, elle, n'est pas transactionnelle. La faire
        // avant laisserait un utilisateur muet dans Firestore alors que PostgreSQL le dit
        // libre — état invisible pour l'admin, donc impossible à lever.
        // UID Firebase, jamais l'UUID PostgreSQL : la règle Firestore ne voit que
        // request.auth.uid.
        firestoreService.setMessagingMute(user.getFirebaseUid(), until);

        log.info("User {} messaging muted until {} by admin", userId, until);
        return saved;
    }

    /** Lève la coupure de messagerie (Lot B) — supprime aussi le document Firestore. */
    @Transactional
    public UserEntity unmuteMessaging(UUID userId, UUID adminId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        user.setMessagingMutedUntil(null);
        UserEntity saved = userRepository.save(user);

        auditService.log("USER", userId, "USER_MESSAGING_UNMUTED", adminId, Map.of());

        // Même ordre que muteMessaging : l'écriture Firestore ferme la marche.
        firestoreService.clearMessagingMute(user.getFirebaseUid());
        log.info("User {} messaging unmuted by admin", userId);
        return saved;
    }
}
