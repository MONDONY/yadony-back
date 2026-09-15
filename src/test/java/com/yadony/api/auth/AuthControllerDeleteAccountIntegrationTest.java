package com.yadony.api.auth;

import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reproduit en réel le bug transactionnel corrigé sur T6 (relecture) : un wallet dont le
 * reliquat remboursable s'arrondit à zéro à l'unité mineure Stripe (XOF, 0 décimale) fait
 * lever {@code wallet-not-refund-eligible} par {@code WalletSelfRefundService.request}, une
 * exception attrapée par {@code UserService#settleWalletsForDeletion} — mais qui, sans
 * {@code noRollbackFor}, marquait la transaction englobante de {@code requestDeletion}
 * rollback-only et faisait échouer son commit en {@code UnexpectedRollbackException} (500)
 * au lieu du 204 attendu.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("DELETE /auth/me — reliquat XOF arrondi à zéro à l'unité mineure")
class AuthControllerDeleteAccountIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired WalletAccountRepository walletAccountRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;

    private static final String FIREBASE_UID = "uid-delete-account-xof-residual";

    private UserEntity user;

    @BeforeEach
    void setUp() {
        walletTransactionRepository.deleteAll();
        walletAccountRepository.deleteAll();
        userRepository.deleteAll();

        user = new UserEntity();
        user.setFirebaseUid(FIREBASE_UID);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user = userRepository.save(user);

        // Solde XOF de 0,50 F CFA, entièrement remboursable au sens du ledger (une seule
        // recharge carte, aucune dépense) mais dont le reliquat s'arrondit à zéro une fois
        // aligné sur l'unité mineure Stripe de XOF (0 décimale) — refundableTotal > 0 en
        // interne (NUMERIC(10,2)), scaledTargets vide dans WalletSelfRefundService.request().
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(user.getId());
        wallet.setCurrency("XOF");
        wallet.setBalance(new BigDecimal("0.50"));
        walletAccountRepository.save(wallet);

        WalletTransactionEntity topup = new WalletTransactionEntity();
        topup.setUserId(user.getId());
        topup.setCurrency("XOF");
        topup.setType(WalletTransactionType.TOP_UP);
        topup.setAmount(new BigDecimal("0.50"));
        topup.setBalanceAfter(new BigDecimal("0.50"));
        topup.setPaymentRef("pi_xof_residual_test");
        walletTransactionRepository.save(topup);
    }

    private UsernamePasswordAuthenticationToken authenticatedAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    @DisplayName("204 No Content, le compte passe PENDING_DELETION malgré le reliquat inéligible")
    void residualBelowMinorUnit_stillSucceeds() throws Exception {
        mockMvc.perform(delete("/auth/me")
                        .with(authentication(authenticatedAs(FIREBASE_UID))))
                .andExpect(status().isNoContent());

        Optional<UserEntity> reloaded = userRepository.findByFirebaseUid(FIREBASE_UID);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
        assertThat(reloaded.get().getDeletionRequestedAt()).isNotNull();
    }
}
