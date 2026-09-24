package com.yadony.api.auth;

import com.yadony.api.admin.account.AdminAuthorities;
import com.yadony.api.admin.account.AdminAuthService;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for GET /auth/me enriched with admin information.
 *
 * Task 10 — /auth/me enriched with admin data
 *
 * Tests:
 * - Regular user (non-admin) → 200 + UserResponse with admin=null
 * - Admin user → 200 + UserResponse with admin block containing role, permissions, mustChangePassword
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("GET /auth/me — enriched with admin data (Task 10)")
class AuthMeIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;

    @MockitoBean
    AdminAuthService adminAuthService;

    @MockitoBean
    FirebaseAuth firebaseAuth;

    /** Téléphone et email ne sont plus en base : c'est Firebase qui les sert. */
    @MockitoBean
    FirebaseContactService firebaseContact;

    private static final String FIREBASE_UID_REGULAR = "uid-regular-001";
    private static final String FIREBASE_UID_ADMIN = "uid-admin-001";

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        // Seed a regular user (non-admin)
        UserEntity regularUser = new UserEntity();
        regularUser.setFirebaseUid(FIREBASE_UID_REGULAR);
        regularUser.setStatus(UserStatus.ACTIVE);
        regularUser.setKycStatus(KycStatus.PENDING);
        regularUser.setRoles(Set.of(Role.SENDER, Role.TRAVELER));
        regularUser.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        userRepository.save(regularUser);

        // Seed an admin user
        UserEntity adminUser = new UserEntity();
        adminUser.setFirebaseUid(FIREBASE_UID_ADMIN);
        adminUser.setStatus(UserStatus.ACTIVE);
        adminUser.setKycStatus(KycStatus.PENDING);
        adminUser.setRoles(Set.of(Role.SENDER));
        adminUser.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        userRepository.save(adminUser);

        org.mockito.Mockito.when(firebaseContact.getContact(FIREBASE_UID_REGULAR))
                .thenReturn(new FirebaseContactService.Contact("+33612000001", null));
        org.mockito.Mockito.when(firebaseContact.getContact(FIREBASE_UID_ADMIN))
                .thenReturn(new FirebaseContactService.Contact("+33612000002", null));
    }

    private UsernamePasswordAuthenticationToken regularUserAuth() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID_REGULAR, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"),
                        new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UsernamePasswordAuthenticationToken superAdminUserAuth() {
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AdminPrincipal principal = new AdminPrincipal(adminId, "admin.1@yadony.test", AdminRole.SUPER_ADMIN, false, FIREBASE_UID_ADMIN);
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(
                        new SimpleGrantedAuthority("ADMIN_MANAGE"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")
                )
        );
    }

    @Test
    @DisplayName("GET /auth/me without admin role → 200 + admin=null")
    void getMe_regularUser_returns200WithoutAdminBlock() throws Exception {
        // Mock: no admin account for this Firebase UID
        when(adminAuthService.resolve(FIREBASE_UID_REGULAR))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/auth/me")
                        .with(authentication(regularUserAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.phoneNumber").value("+33612000001"))
                .andExpect(jsonPath("$.admin").doesNotExist())
                // V264 : préférence par défaut, aucun compte de ce jeu de tests n'a
                // appelé PATCH /users/me/preferences.
                .andExpect(jsonPath("$.preferredLanguage").value("fr"));
    }

    @Test
    @DisplayName("GET /auth/me — token valide mais aucune ligne users → 404, jamais 401")
    void getMe_authenticatedButNotRegistered_returns404() throws Exception {
        // Contrat dont dépend le client : « pas encore inscrit » doit se distinguer de
        // « session invalide ». Un 401 ici renverrait tout nouvel utilisateur — ou tout
        // le monde après une remise à zéro de la base de dev — vers l'écran de login
        // au lieu de l'onboarding.
        String inconnu = "uid-jamais-inscrit-999";
        when(adminAuthService.resolve(inconnu)).thenReturn(Optional.empty());
        var auth = new UsernamePasswordAuthenticationToken(inconnu, null, List.of());

        mockMvc.perform(get("/auth/me").with(authentication(auth)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
    }

    @Test
    @DisplayName("GET /auth/me with SUPER_ADMIN → 200 + admin block with role + permissions + mustChangePassword")
    void getMe_superAdminUser_returns200WithAdminBlock() throws Exception {
        // Build AdminAuthorities for SUPER_ADMIN with ADMIN_MANAGE permission
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Set<org.springframework.security.core.GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority("ADMIN_MANAGE"));
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

        AdminAuthorities adminAuthorities = new AdminAuthorities(
                AdminRole.SUPER_ADMIN,
                authorities,
                false, // mustChangePassword
                "admin.1@yadony.test",
                adminId
        );

        when(adminAuthService.resolve(FIREBASE_UID_ADMIN))
                .thenReturn(Optional.of(adminAuthorities));

        mockMvc.perform(get("/auth/me")
                        .with(authentication(superAdminUserAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+33612000002"))
                .andExpect(jsonPath("$.admin").exists())
                .andExpect(jsonPath("$.admin.email").value("admin.1@yadony.test"))
                .andExpect(jsonPath("$.admin.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.admin.mustChangePassword").value(false))
                .andExpect(jsonPath("$.admin.permissions").isArray())
                .andExpect(jsonPath("$.admin.permissions", hasItem("ADMIN_MANAGE")));
    }
}
