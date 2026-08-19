package com.yadony.api.payments.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot 3 (2026-08-19/20) : le parrainage ne crédite plus le wallet — il octroie un bon
 * de réduction de commission (voir {@code com.yadony.api.voucher}). REFERRAL_REWARD
 * reste une valeur valide de {@link WalletTransactionType} et de la contrainte CHECK
 * V121 (lignes historiques déjà écrites), mais plus rien ne l'émet. Ce test garde
 * uniquement la preuve que la contrainte CHECK accepte toujours ce type — le reste
 * (listener, event) a été retiré avec son test dédié.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReferralRewardWalletIT {

    @Autowired private WalletService walletService;

    @Test
    void credit_withReferralRewardType_passesDbCheckConstraint() {
        UUID userId = UUID.randomUUID();

        // Would throw a constraint-violation if V121 hadn't extended wallet_transactions_type_check
        walletService.credit(userId, "EUR", new BigDecimal("5.00"),
                WalletTransactionType.REFERRAL_REWARD, "ref-1", "referral-reward-it-1");

        assertThat(walletService.getBalance(userId, "EUR")).isEqualByComparingTo(new BigDecimal("5.00"));
    }
}
