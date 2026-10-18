package com.yadony.api.auth;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminStatus;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * YADONY-BACK-PROD-6 à B : un compte admin connecté à l'application mobile recevait un
 * AdminPrincipal sur toutes les routes, et chaque {@code (String) getPrincipal()} des
 * contrôleurs utilisateur échouait en 500 (GET /wallet/balance en tête). Ici, le vrai
 * FirebaseTokenFilter tourne, seule la vérification du jeton Firebase est simulée.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Compte admin dans l'app : utilisateur normal hors back-office")
class AdminAccountInAppIT {

    private static final String TOKEN = "admin-in-app-token";

    @Autowired MockMvc mockMvc;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired UserRepository userRepository;

    private MockedStatic<FirebaseAuth> staticAuth;
    private MockedStatic<FirebaseApp> staticApp;
    private String uid;

    @BeforeEach
    void setUp() throws Exception {
        uid = "uid-admin-app-" + UUID.randomUUID();
        AdminUserEntity admin = new AdminUserEntity(uid, "admin-app-" + UUID.randomUUID() + "@yadony.test",
                AdminRole.SUPER_ADMIN);
        admin.setStatus(AdminStatus.ACTIVE);
        admin.setMustChangePassword(false);
        adminUserRepository.saveAndFlush(admin);

        UserEntity user = new UserEntity();
        user.setFirebaseUid(uid);
        user.setFirstName("Awa");
        user.setLastName("Diallo");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(new HashSet<>(Set.of(Role.SENDER)));
        user.setTotalTrips(0);
        userRepository.saveAndFlush(user);

        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        FirebaseToken decoded = mock(FirebaseToken.class);
        when(decoded.getUid()).thenReturn(uid);
        when(firebaseAuth.verifyIdToken(TOKEN)).thenReturn(decoded);
        staticApp = mockStatic(FirebaseApp.class);
        staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
        staticAuth = mockStatic(FirebaseAuth.class);
        staticAuth.when(FirebaseAuth::getInstance).thenReturn(firebaseAuth);
    }

    @AfterEach
    void tearDown() {
        staticAuth.close();
        staticApp.close();
    }

    @Test
    @DisplayName("GET /wallet/balance avec un uid présent dans admin_users → 200, plus de ClassCastException")
    void walletBalance_adminAccount_200() throws Exception {
        mockMvc.perform(get("/wallet/balance").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("le même jeton garde ses droits sur le back-office (GET /admin/me)")
    void adminMe_sameToken_stillAdmin() throws Exception {
        mockMvc.perform(get("/admin/me").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"));
    }
}
