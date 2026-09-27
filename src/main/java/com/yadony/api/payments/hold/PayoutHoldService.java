package com.yadony.api.payments.hold;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Etat de gel des versements d'un voyageur (banni, ou verification d'identite retiree).
 *
 * <p>Vit dans {@code payments/} : c'est une regle de versement, pas une propriete du compte. Les
 * packages {@code auth/} et {@code kyc/} ne la connaissent pas, ils publient seulement leurs
 * evenements ({@link PayoutHoldEventListener}).
 *
 * <p>Un gel n'arrete que les GAINS du voyageur. Rien ne repart automatiquement a la levee : les
 * paiements retenus restent ESCROW, l'administrateur est alerte et les libere a la main.
 */
@Service
public class PayoutHoldService implements PayoutHoldPolicy {

    private static final Logger log = LoggerFactory.getLogger(PayoutHoldService.class);

    static final String LIFTED_ALERT = "PAYOUT_HOLD_LIFTED";

    private final PayoutHoldRepository repository;
    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final AdminAlertService adminAlert;

    public PayoutHoldService(PayoutHoldRepository repository, PaymentRepository paymentRepository,
                             AuditService auditService, AdminAlertService adminAlert) {
        this.repository = repository;
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.adminAlert = adminAlert;
    }

    /**
     * Gele les versements de {@code userId} pour {@code reason}. Idempotent : un motif deja actif
     * n'est ni recree ni re-audite.
     *
     * @return {@code true} si un gel a ete cree
     */
    @Transactional
    public boolean hold(UUID userId, PayoutHoldReason reason, UUID actorId) {
        if (userId == null) {
            return false;
        }
        if (repository.findActive(userId, reason).isPresent()) {
            return false;
        }
        repository.save(new PayoutHoldEntity(userId, reason, now(), actorId));
        auditService.log("USER", userId, "PAYOUTS_HELD", actorId, Map.of("reason", reason.name()));
        log.info("Versements geles pour l'utilisateur {} ({})", userId, reason);
        return true;
    }

    /**
     * Leve le gel {@code reason} de {@code userId}. Le voyageur reste gele si l'autre motif est
     * encore actif. Quand plus aucun gel ne subsiste et que des paiements ont ete retenus, une
     * alerte en donne le nombre : ils ne repartent pas seuls.
     *
     * @return {@code true} si un gel actif a ete leve
     */
    @Transactional
    public boolean release(UUID userId, PayoutHoldReason reason, UUID actorId) {
        if (userId == null) {
            return false;
        }
        PayoutHoldEntity hold = repository.findActive(userId, reason).orElse(null);
        if (hold == null) {
            return false;
        }
        hold.release(now(), actorId);
        repository.save(hold);

        List<PayoutHoldReason> remaining = repository.findActiveByUserId(userId).stream()
                .filter(h -> h != hold)
                .map(PayoutHoldEntity::getReason)
                .filter(r -> r != reason)
                .distinct()
                .sorted()
                .toList();
        long heldPayments = paymentRepository.countHeldEscrowForTraveler(userId);

        auditService.log("USER", userId, "PAYOUTS_RELEASED_HOLD", actorId, Map.of(
                "reason", reason.name(),
                "remainingReasons", remaining.stream().map(Enum::name).collect(Collectors.joining(",")),
                "heldPaymentsCount", heldPayments));
        log.info("Gel {} leve pour l'utilisateur {} (motifs restants : {}, paiements retenus : {})",
                reason, userId, remaining, heldPayments);

        if (remaining.isEmpty() && heldPayments > 0) {
            Map<String, Object> context = Map.of(
                    "userId", userId.toString(),
                    "reason", reason.name(),
                    "heldPaymentsCount", String.valueOf(heldPayments));
            String detail = "Gel des versements leve pour le voyageur " + userId + " : " + heldPayments
                    + " paiement(s) ESCROW retenu(s) a liberer a la main (GET /admin/payments?held=true)";
            afterCommit(() -> adminAlert.raise(LIFTED_ALERT, detail, context));
        }
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public PayoutHoldStatus statusOf(UUID userId) {
        if (userId == null) {
            return PayoutHoldStatus.NONE;
        }
        return toStatus(repository.findActiveByUserId(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PayoutHoldStatus> statusesOf(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<PayoutHoldEntity>> byUser = repository.findActiveByUserIds(ids).stream()
                .collect(Collectors.groupingBy(PayoutHoldEntity::getUserId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, PayoutHoldStatus> statuses = new LinkedHashMap<>();
        byUser.forEach((id, holds) -> statuses.put(id, toStatus(holds)));
        return statuses;
    }

    /** Etat du gel et nombre de paiements ESCROW retenus, pour la fiche utilisateur admin. */
    @Transactional(readOnly = true)
    public PayoutHoldSummary summaryOf(UUID userId) {
        if (userId == null) {
            return PayoutHoldSummary.NONE;
        }
        PayoutHoldStatus status = statusOf(userId);
        return new PayoutHoldSummary(status.heldSince(), status.reasons(),
                paymentRepository.countHeldEscrowForTraveler(userId));
    }

    private static PayoutHoldStatus toStatus(List<PayoutHoldEntity> holds) {
        if (holds == null || holds.isEmpty()) {
            return PayoutHoldStatus.NONE;
        }
        LocalDateTime since = holds.stream().map(PayoutHoldEntity::getHeldSince)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        List<PayoutHoldReason> reasons = holds.stream().map(PayoutHoldEntity::getReason)
                .distinct().sorted().toList();
        return new PayoutHoldStatus(since, reasons);
    }

    /**
     * L'alerte part apres le commit : envoyee avant, elle annoncerait une levee qu'un rollback
     * pourrait encore annuler. Hors transaction, elle part tout de suite.
     */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
