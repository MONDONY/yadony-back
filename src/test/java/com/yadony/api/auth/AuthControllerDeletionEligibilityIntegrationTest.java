package com.yadony.api.auth;

import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET /auth/me/deletion-eligibility}.
 *
 * <p>Uses {@code @ActiveProfiles("test")} to leverage the H2 in-memory DB. Le cas
 * "escrow actif" est déjà couvert en profondeur par {@code UserServiceTest} (mocks) —
 * seul le cas solde wallet, bon marché à semer en base réelle, est ajouté ici en plus
 * du chemin nominal, pour vérifier le câblage controller → service → repository.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("GET /auth/me/deletion-eligibility — integration tests")
class AuthControllerDeletionEligibilityIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired WalletAccountRepository walletAccountRepository;

    private static final String FIREBASE_UID_ELIGIBLE = "uid-deletion-elig-001";
    private static final String FIREBASE_UID_WALLET_BALANCE = "uid-deletion-elig-002";

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        walletAccountRepository.deleteAll();

        UserEntity eligible = new UserEntity();
        eligible.setFirebaseUid(FIREBASE_UID_ELIGIBLE);
        eligible.setStatus(UserStatus.ACTIVE);
        eligible.setKycStatus(KycStatus.PENDING);
        eligible.setRoles(Set.of(Role.SENDER));
        userRepository.save(eligible);

        UserEntity walletBlocked = new UserEntity();
        walletBlocked.setFirebaseUid(FIREBASE_UID_WALLET_BALANCE);
        walletBlocked.setStatus(UserStatus.ACTIVE);
        walletBlocked.setKycStatus(KycStatus.PENDING);
        walletBlocked.setRoles(Set.of(Role.SENDER));
        UserEntity savedWalletBlocked = userRepository.save(walletBlocked);

        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(savedWalletBlocked.getId());
        wallet.setBalance(new BigDecimal("12.50"));
        walletAccountRepository.save(wallet);
    }

    private UsernamePasswordAuthenticationToken authenticatedAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    @DisplayName("200 OK, canDelete=true, blockedReasonCode=null, hasWalletBalance=false quand rien ne bloque")
    void noBlockers_returnsEligible() throws Exception {
        mockMvc.perform(get("/auth/me/deletion-eligibility")
                        .with(authentication(authenticatedAs(FIREBASE_UID_ELIGIBLE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDelete").value(true))
                .andExpect(jsonPath("$.blockedReasonCode").doesNotExist())
                .andExpect(jsonPath("$.hasWalletBalance").value(false));
    }

    @Test
    @DisplayName("200 OK, canDelete=true malgré un solde wallet positif (Apple 5.1.1(v)), hasWalletBalance=true")
    void positiveWalletBalance_doesNotBlockButIsSignaled() throws Exception {
        mockMvc.perform(get("/auth/me/deletion-eligibility")
                        .with(authentication(authenticatedAs(FIREBASE_UID_WALLET_BALANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDelete").value(true))
                .andExpect(jsonPath("$.blockedReasonCode").doesNotExist())
                .andExpect(jsonPath("$.hasWalletBalance").value(true));
    }

    @Test
    @DisplayName("401 Unauthorized sans authentification")
    void noAuth_returns401() throws Exception {
        mockMvc.perform(get("/auth/me/deletion-eligibility"))
                .andExpect(status().isUnauthorized());
    }
}
