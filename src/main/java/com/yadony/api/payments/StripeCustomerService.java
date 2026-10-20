package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.Balance;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Nettoyage Stripe d'un compte finalisé (suppression RGPD) : fermeture du compte Connect du
 * voyageur. Le mobile money (pawaPay) n'a aucun compte chez un tiers et n'est pas concerné.
 *
 * <ol>
 *   <li>Solde du compte connecté relu ({@code available} + {@code pending}, toutes devises). S'il
 *       reste de l'argent (ou une dette, solde négatif), rien n'est fermé : alerte admin, le compte
 *       reste rattaché à l'utilisateur pour être traité à la main.</li>
 *   <li>Solde nul et aucun mouvement en cours (versement réservé {@code PENDING} vers ce compte,
 *       séquestre actif, virement vers la banque en attente ou en transit) : le compte est
 *       supprimé ({@code DELETE /v1/accounts/{id}}). Si Stripe refuse la suppression, il est
 *       rejeté.</li>
 *   <li>Solde nul mais mouvement en cours : le compte est rejeté (plus aucun paiement ni
 *       versement), et l'admin prévenu de l'argent encore attendu.</li>
 *   <li>Compte supprimé ou rejeté : {@code stripeAccountId} et {@code stripeAccountStatus} sont
 *       vidés sur l'utilisateur.</li>
 * </ol>
 *
 * <p>Jamais bloquant : la finalisation est déjà commitée. Les écritures (utilisateur, audit) ont
 * leur propre transaction : un échec de forfeit du wallet rend la transaction de
 * {@link UserFinalizedPaymentsListener} rollback-only, et ne doit pas effacer la trace d'une
 * fermeture déjà faite chez Stripe.
 */
@Service
public class StripeCustomerService {

    private static final Logger log = LoggerFactory.getLogger(StripeCustomerService.class);

    static final String ALERT_CODE = "STRIPE_CONNECT_ACCOUNT_NOT_CLOSED";
    static final String REJECT_REASON = "other";

    /** Issue de la fermeture du compte Connect. */
    enum ConnectCleanup { NO_ACCOUNT, DELETED, REJECTED, KEPT }

    private final StripeGateway stripeGateway;
    private final StripeReleaseIntentRepository releaseIntentRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AdminAlertService adminAlertService;
    private final TransactionTemplate ownTransaction;

    public StripeCustomerService(StripeGateway stripeGateway,
                                 StripeReleaseIntentRepository releaseIntentRepository,
                                 PaymentRepository paymentRepository,
                                 UserRepository userRepository,
                                 AuditService auditService,
                                 AdminAlertService adminAlertService,
                                 PlatformTransactionManager transactionManager) {
        this.stripeGateway = stripeGateway;
        this.releaseIntentRepository = releaseIntentRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.adminAlertService = adminAlertService;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void cleanupForUser(UserEntity user) {
        // Stripe Customer ID : pas stocké sur UserEntity, rien à supprimer côté client.
        ConnectCleanup outcome = closeConnectAccount(user.getId(), user.getStripeAccountId());
        log.info("Nettoyage Stripe du compte finalisé {} : Connect {}", user.getId(), outcome);
    }

    ConnectCleanup closeConnectAccount(UUID userId, String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return ConnectCleanup.NO_ACCOUNT;
        }

        List<String> remaining;
        boolean payoutInFlight;
        try {
            remaining = nonZeroBalances(stripeGateway.retrieveConnectedBalance(accountId));
            payoutInFlight = hasPayout(accountId, "pending") || hasPayout(accountId, "in_transit");
        } catch (StripeException | RuntimeException e) {
            keep(userId, accountId, "BALANCE_UNREADABLE", List.of(),
                    "Compte finalisé : solde Stripe Connect illisible (" + e.getClass().getSimpleName()
                            + "), compte laissé ouvert. Vérifier le solde et le fermer à la main.");
            return ConnectCleanup.KEPT;
        }

        if (!remaining.isEmpty()) {
            keep(userId, accountId, "BALANCE_REMAINING", remaining,
                    "Compte finalisé avec un solde Stripe Connect non nul : compte laissé ouvert. "
                            + "Régler le solde puis fermer le compte depuis le tableau de bord Stripe.");
            return ConnectCleanup.KEPT;
        }

        boolean transferInFlight = payoutInFlight
                || releaseIntentRepository.existsByDestinationAndStatus(accountId,
                        StripeReleaseIntentEntity.Status.PENDING)
                || paymentRepository.hasActiveEscrowForUser(userId);

        ConnectCleanup outcome;
        if (transferInFlight) {
            outcome = reject(userId, accountId);
            if (outcome == ConnectCleanup.REJECTED) {
                adminAlertService.raise(ALERT_CODE,
                        "Compte finalisé : compte Stripe Connect rejeté mais un mouvement d'argent est "
                                + "encore en cours (versement ou séquestre). Le suivre à la main.",
                        context(userId, accountId, "TRANSFER_IN_FLIGHT", List.of()));
            }
        } else {
            outcome = deleteOrReject(userId, accountId);
        }
        if (outcome == ConnectCleanup.KEPT) {
            return outcome;
        }

        ConnectCleanup closed = outcome;
        ownTransaction.executeWithoutResult(status -> {
            userRepository.clearStripeAccount(userId, accountId);
            Map<String, Object> payload = new HashMap<>();
            payload.put("stripeAccountId", accountId);
            payload.put("outcome", closed.name());
            payload.put("transferInFlight", transferInFlight);
            auditService.log("USER", userId, "STRIPE_CONNECT_ACCOUNT_CLOSED", null, payload);
        });
        return closed;
    }

    private ConnectCleanup deleteOrReject(UUID userId, String accountId) {
        try {
            stripeGateway.deleteAccount(accountId);
            return ConnectCleanup.DELETED;
        } catch (StripeException | RuntimeException e) {
            log.warn("Suppression du compte Connect {} refusée par Stripe ({}), rejet à la place",
                    accountId, e.getMessage());
            return reject(userId, accountId);
        }
    }

    private ConnectCleanup reject(UUID userId, String accountId) {
        try {
            stripeGateway.rejectAccount(accountId, REJECT_REASON);
            return ConnectCleanup.REJECTED;
        } catch (StripeException | RuntimeException e) {
            keep(userId, accountId, "CLOSE_FAILED", List.of(),
                    "Compte finalisé : Stripe a refusé la suppression et le rejet du compte Connect ("
                            + e.getMessage() + "). Le fermer depuis le tableau de bord Stripe.");
            return ConnectCleanup.KEPT;
        }
    }

    private boolean hasPayout(String accountId, String status) throws StripeException {
        var payouts = stripeGateway.listConnectedPayouts(accountId, status);
        return payouts != null && payouts.getData() != null && !payouts.getData().isEmpty();
    }

    /** Montants non nuls, en unités mineures : {@code available:1250 eur}, {@code pending:-300 eur}. */
    static List<String> nonZeroBalances(Balance balance) {
        List<String> remaining = new ArrayList<>();
        if (balance == null) {
            return remaining;
        }
        if (balance.getAvailable() != null) {
            for (Balance.Available a : balance.getAvailable()) {
                if (a.getAmount() != null && a.getAmount() != 0L) {
                    remaining.add("available:" + a.getAmount() + " " + a.getCurrency());
                }
            }
        }
        if (balance.getPending() != null) {
            for (Balance.Pending p : balance.getPending()) {
                if (p.getAmount() != null && p.getAmount() != 0L) {
                    remaining.add("pending:" + p.getAmount() + " " + p.getCurrency());
                }
            }
        }
        return remaining;
    }

    private void keep(UUID userId, String accountId, String reason, List<String> balances, String detail) {
        adminAlertService.raise(ALERT_CODE, detail, context(userId, accountId, reason, balances));
        Map<String, Object> payload = new HashMap<>();
        payload.put("stripeAccountId", accountId);
        payload.put("reason", reason);
        if (!balances.isEmpty()) {
            payload.put("balances", String.join(", ", balances));
        }
        try {
            ownTransaction.executeWithoutResult(status ->
                    auditService.log("USER", userId, "STRIPE_CONNECT_ACCOUNT_KEPT", null, payload));
        } catch (RuntimeException e) {
            log.warn("Audit du compte Connect {} laissé ouvert impossible : {}", accountId, e.getMessage());
        }
    }

    private static Map<String, Object> context(UUID userId, String accountId, String reason, List<String> balances) {
        Map<String, Object> context = new HashMap<>();
        context.put("userId", userId);
        context.put("stripeAccountId", accountId);
        context.put("reason", reason);
        if (!balances.isEmpty()) {
            context.put("balances", String.join(", ", balances));
        }
        return context;
    }
}
