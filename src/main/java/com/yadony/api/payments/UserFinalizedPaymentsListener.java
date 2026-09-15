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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * À la finalisation d'un compte : nettoyage du client Stripe (existant) et perte de la
 * part non-cash du wallet ({@code FORFEITED_ON_DELETION}). Le cash encore en cours de
 * remboursement (ou repris par un ticket manuel) n'est jamais touché. Idempotent : un
 * second passage ne trouve plus de non-cash.
 */
@Component
public class UserFinalizedPaymentsListener {

    private static final Logger log = LoggerFactory.getLogger(UserFinalizedPaymentsListener.class);

    private final StripeCustomerService stripeCustomerService;
    private final UserRepository userRepository;
    private final WalletAccountRepository walletAccountRepository;
    private final WalletSelfRefundService walletSelfRefundService;
    private final WalletService walletService;

    public UserFinalizedPaymentsListener(StripeCustomerService stripeCustomerService,
                                         UserRepository userRepository,
                                         WalletAccountRepository walletAccountRepository,
                                         WalletSelfRefundService walletSelfRefundService,
                                         WalletService walletService) {
        this.stripeCustomerService = stripeCustomerService;
        this.userRepository = userRepository;
        this.walletAccountRepository = walletAccountRepository;
        this.walletSelfRefundService = walletSelfRefundService;
        this.walletService = walletService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserFinalized(UserFinalizedEvent event) {
        forfeitNonCash(event.getUserId());
        userRepository.findById(event.getUserId())
                .ifPresent(stripeCustomerService::cleanupForUser);
    }

    private void forfeitNonCash(UUID userId) {
        for (WalletAccountEntity wallet : walletAccountRepository.findAllByUserId(userId)) {
            if (wallet.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            try {
                WalletRefundAllocation allocation = walletSelfRefundService.allocation(userId, wallet.getCurrency());
                if (allocation.nonRefundable().signum() > 0) {
                    walletService.debitConfirmedRefund(userId, wallet.getCurrency(),
                            allocation.nonRefundable(), WalletTransactionType.FORFEITED_ON_DELETION);
                    log.info("Non-cash perdu a la finalisation pour user {} : {} {}",
                            userId, allocation.nonRefundable(), wallet.getCurrency());
                }
            } catch (WalletAllocationInvariantException e) {
                log.warn("Finalisation user {} : allocation {} incoherente, non-cash non debite ({})",
                        userId, wallet.getCurrency(), e.getMessage());
            }
        }
    }
}
