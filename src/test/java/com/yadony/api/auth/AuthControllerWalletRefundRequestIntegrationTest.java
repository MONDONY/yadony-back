package com.yadony.api.auth;

import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.payments.wallet.WalletRefundRequestRepository;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code POST /auth/me/wallet-refund-request} — même style H2 réel
 * que {@link AuthControllerDeletionEligibilityIntegrationTest}, dont ce cas (solde wallet
 * bloquant) est le pendant côté résolution : ici on ouvre le ticket, pas juste on le lit.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("POST /auth/me/wallet-refund-request — integration tests")
class AuthControllerWalletRefundRequestIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired WalletAccountRepository walletAccountRepository;
    @Autowired WalletRefundRequestRepository walletRefundRequestRepository;

    private static final String FIREBASE_UID_WITH_BALANCE = "uid-wallet-refund-001";
    private static final String FIREBASE_UID_NO_BALANCE = "uid-wallet-refund-002";

    private UserEntity withBalance;

    @BeforeEach
    void setUp() {
        walletRefundRequestRepository.deleteAll();
        walletAccountRepository.deleteAll();
        userRepository.deleteAll();

        withBalance = new UserEntity();
        withBalance.setFirebaseUid(FIREBASE_UID_WITH_BALANCE);
        withBalance.setStatus(UserStatus.ACTIVE);
        withBalance.setKycStatus(KycStatus.PENDING);
        withBalance.setRoles(Set.of(Role.SENDER));
        withBalance = userRepository.save(withBalance);

        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(withBalance.getId());
        wallet.setCurrency("CAD");
        wallet.setBalance(new BigDecimal("45.00"));
        walletAccountRepository.save(wallet);

        UserEntity noBalance = new UserEntity();
        noBalance.setFirebaseUid(FIREBASE_UID_NO_BALANCE);
        noBalance.setStatus(UserStatus.ACTIVE);
        noBalance.setKycStatus(KycStatus.PENDING);
        noBalance.setRoles(Set.of(Role.SENDER));
        userRepository.save(noBalance);
    }

    private UsernamePasswordAuthenticationToken authenticatedAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    @DisplayName("solde CAD positif → 200, ticket PENDING créé en base avec le bon montant/devise")
    void positiveBalance_createsPendingTicket() throws Exception {
        mockMvc.perform(post("/auth/me/wallet-refund-request")
                        .with(authentication(authenticatedAs(FIREBASE_UID_WITH_BALANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].currency").value("CAD"))
                .andExpect(jsonPath("$[0].amount").value(45.00));

        assertThat(walletRefundRequestRepository.findAllByUserIdAndStatus(
                withBalance.getId(), com.yadony.api.payments.wallet.WalletRefundRequestStatus.PENDING))
                .hasSize(1);
    }

    @Test
    @DisplayName("aucun solde positif → 422 wallet-balance-empty")
    void noBalance_returns422() throws Exception {
        mockMvc.perform(post("/auth/me/wallet-refund-request")
                        .with(authentication(authenticatedAs(FIREBASE_UID_NO_BALANCE))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("wallet-balance-empty"));
    }

    @Test
    @DisplayName("appel répété → même ticket réutilisé, pas de doublon en base")
    void calledTwice_doesNotDuplicateTicket() throws Exception {
        mockMvc.perform(post("/auth/me/wallet-refund-request")
                        .with(authentication(authenticatedAs(FIREBASE_UID_WITH_BALANCE))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/me/wallet-refund-request")
                        .with(authentication(authenticatedAs(FIREBASE_UID_WITH_BALANCE))))
                .andExpect(status().isOk());

        assertThat(walletRefundRequestRepository.findAllByUserIdAndStatus(
                withBalance.getId(), com.yadony.api.payments.wallet.WalletRefundRequestStatus.PENDING))
                .hasSize(1);
    }

    @Test
    @DisplayName("401 Unauthorized sans authentification")
    void noAuth_returns401() throws Exception {
        mockMvc.perform(post("/auth/me/wallet-refund-request"))
                .andExpect(status().isUnauthorized());
    }
}
