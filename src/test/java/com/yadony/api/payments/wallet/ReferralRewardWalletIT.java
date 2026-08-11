package com.yadony.api.payments.wallet;

import com.yadony.api.referral.events.ReferralRewardGrantedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: proves the referral reward actually lands in the spendable wallet,
 * exercising the V121 CHECK-constraint update (REFERRAL_REWARD type) against the real
 * (H2/PostgreSQL-mode) schema, plus the AFTER_COMMIT event listener wiring.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReferralRewardWalletIT {

    @Autowired private WalletService walletService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    void credit_withReferralRewardType_passesDbCheckConstraint() {
        UUID userId = UUID.randomUUID();

        // Would throw a constraint-violation if V121 hadn't extended wallet_transactions_type_check
        walletService.credit(userId, "EUR", new BigDecimal("5.00"),
                WalletTransactionType.REFERRAL_REWARD, "ref-1", "referral-reward-it-1");

        assertThat(walletService.getBalance(userId, "EUR")).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    @Test
    void referralRewardGrantedEvent_creditsWalletAfterCommit() {
        UUID referrerId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();

        // Publish inside a committed transaction so the AFTER_COMMIT listener fires
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(
                        new ReferralRewardGrantedEvent(referrerId, 500, invitationId)));

        assertThat(walletService.getBalance(referrerId, "EUR")).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    @Test
    void referralRewardGrantedEvent_isIdempotentPerInvitation() {
        UUID referrerId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        ReferralRewardGrantedEvent ev = new ReferralRewardGrantedEvent(referrerId, 500, invitationId);

        transactionTemplate.executeWithoutResult(s -> eventPublisher.publishEvent(ev));
        transactionTemplate.executeWithoutResult(s -> eventPublisher.publishEvent(ev));

        // Same invitation → credited exactly once (idempotency key referral-reward-{invitationId})
        assertThat(walletService.getBalance(referrerId, "EUR")).isEqualByComparingTo(new BigDecimal("5.00"));
    }
}
