package com.yadony.api.payments;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.UserFinalizedEvent;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.payments.wallet.WalletAllocationInvariantException;
import com.yadony.api.payments.wallet.WalletRefundAllocation;
import com.yadony.api.payments.wallet.WalletSelfRefundService;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * À la finalisation d'un compte : fermeture du compte Stripe Connect et perte de la
 * part non-cash du wallet ({@code FORFEITED_ON_DELETION}). Le cash encore en cours de
 * remboursement (ou repris par un ticket manuel) n'est jamais touché. Idempotent : un
 * second passage ne trouve plus de non-cash.
 *
 * <p>Chaque devise est forfaitée dans sa propre transaction : un échec sur l'une (solde qui a
 * bougé, base indisponible) n'annule qu'elle. Avant, l'exception marquait la transaction de
 * {@link #onUserFinalized} rollback-only et annulait au commit les forfeits de toutes les autres
 * devises, alors que l'événement n'est publié qu'une fois : rien ne les rejouait. L'appel Stripe
 * qui suit est externe à la base et s'exécute dans tous les cas.
 */
@Component
public class UserFinalizedPaymentsListener {

    private static final Logger log = LoggerFactory.getLogger(UserFinalizedPaymentsListener.class);

    private final StripeCustomerService stripeCustomerService;
    private final UserRepository userRepository;
    private final WalletAccountRepository walletAccountRepository;
    private final WalletSelfRefundService walletSelfRefundService;
    private final WalletService walletService;
    private final TransactionTemplate perCurrency;

    public UserFinalizedPaymentsListener(StripeCustomerService stripeCustomerService,
                                         UserRepository userRepository,
                                         WalletAccountRepository walletAccountRepository,
                                         WalletSelfRefundService walletSelfRefundService,
                                         WalletService walletService,
                                         PlatformTransactionManager transactionManager) {
        this.stripeCustomerService = stripeCustomerService;
        this.userRepository = userRepository;
        this.walletAccountRepository = walletAccountRepository;
        this.walletSelfRefundService = walletSelfRefundService;
        this.walletService = walletService;
        this.perCurrency = new TransactionTemplate(transactionManager);
        this.perCurrency.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserFinalized(UserFinalizedEvent event) {
        forfeitNonCash(event.getUserId());
        // IncludingDeleted : le compte finalisé porte deleted_at, et findById (@Where) ne le
        // trouvait jamais : le nettoyage Stripe ne tournait pour aucun compte supprimé.
        userRepository.findByIdIncludingDeleted(event.getUserId())
                .ifPresent(stripeCustomerService::cleanupForUser);
    }

    private void forfeitNonCash(UUID userId) {
        for (WalletAccountEntity wallet : walletAccountRepository.findAllByUserId(userId)) {
            if (wallet.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            try {
                perCurrency.executeWithoutResult(status -> forfeitCurrency(userId, wallet.getCurrency()));
            } catch (WalletAllocationInvariantException e) {
                log.warn("Finalisation user {} : allocation {} incoherente, non-cash non debite ({})",
                        userId, wallet.getCurrency(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Finalisation user {} : forfeit {} impossible, le nettoyage Stripe continue",
                        userId, wallet.getCurrency(), e);
            }
        }
    }

    /** Perte de la part non-cash d'une devise ; appelée dans sa propre transaction. */
    private void forfeitCurrency(UUID userId, String currency) {
        WalletRefundAllocation allocation = walletSelfRefundService.allocation(userId, currency);
        if (allocation.nonRefundable().signum() > 0) {
            walletService.debitConfirmedRefund(userId, currency,
                    allocation.nonRefundable(), WalletTransactionType.FORFEITED_ON_DELETION);
            log.info("Non-cash perdu a la finalisation pour user {} : {} {}",
                    userId, allocation.nonRefundable(), currency);
        }
    }
}
