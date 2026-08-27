package com.yadony.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.ProGrantRequest;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.billing.ProSubscriptionEntity;
import com.yadony.api.billing.ProSubscriptionRepository;
import com.yadony.api.billing.ProSubscriptionSource;
import com.yadony.api.billing.ProSubscriptionStatus;
import com.yadony.api.auth.KycStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminProGrantControllerIT — /admin/users/{userId}/pro-grant")
class AdminProGrantControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ProSubscriptionRepository subscriptionRepository;

    @MockitoBean FirebaseContactService firebaseContact;

    private UUID userId;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-pro-grant-001");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER));
        user.setCountry("FR");
        userId = userRepository.save(user).getId();

        org.mockito.Mockito.lenient().when(firebaseContact.getContact(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new FirebaseContactService.Contact("+33612000010", null));
    }

    /** Administrateur disposant de toutes les permissions de son rôle. */
    private UsernamePasswordAuthenticationToken adminAuth(AdminRole role) {
        AdminPrincipal principal =
                new AdminPrincipal(ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-pro-grant");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                role.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    /** Administrateur authentifié mais privé de la permission d'octroi. */
    private UsernamePasswordAuthenticationToken adminWithoutPermission() {
        AdminPrincipal principal =
                new AdminPrincipal(UUID.randomUUID(), "sans@yadony.test", AdminRole.ADMIN, false, "uid-sans");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private ProSubscriptionEntity persist(ProSubscriptionStatus status, ProSubscriptionSource source) {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setUserId(userId);
        sub.setStatus(status);
        sub.setSource(source);
        return subscriptionRepository.save(sub);
    }

    @Test
    @DisplayName("l'octroi rend le compte PRO et journalise l'administrateur")
    void grantMakesAccountPro() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/pro-grant", userId)
                        .with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ProGrantRequest("Partenariat presse"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isProAccount").value(true));

        assertThat(userRepository.findById(userId).orElseThrow().isProAccount()).isTrue();
        ProSubscriptionEntity sub = subscriptionRepository.findByUserId(userId).orElseThrow();
        assertThat(sub.getSource()).isEqualTo(ProSubscriptionSource.ADMIN_GRANT);
        assertThat(sub.getGrantedByAdminId()).isEqualTo(ADMIN_ID);
        assertThat(sub.getAdminGrantReason()).isEqualTo("Partenariat presse");
    }

    @Test
    @DisplayName("un motif vide est refusé en 422")
    void blankReasonIsRejected() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/pro-grant", userId)
                        .with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProGrantRequest("   "))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("un administrateur sans la permission est refusé en 403")
    void withoutPermissionIsForbidden() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/pro-grant", userId)
                        .with(authentication(adminWithoutPermission()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProGrantRequest("Test"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("la révocation d'un octroi administrateur ferme l'accès")
    void revokeClosesAdminGrant() throws Exception {
        persist(ProSubscriptionStatus.ACTIVE, ProSubscriptionSource.ADMIN_GRANT);
        UserEntity user = userRepository.findById(userId).orElseThrow();
        user.setProAccount(true);
        userRepository.save(user);

        mockMvc.perform(delete("/admin/users/{userId}/pro-grant", userId)
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isProAccount").value(false));

        assertThat(subscriptionRepository.findByUserId(userId).orElseThrow().getStatus())
                .isEqualTo(ProSubscriptionStatus.CANCELED);
    }

    @Test
    @DisplayName("révoquer un abonnement Stripe payant est refusé en 409")
    void revokingStripeSubscriptionIsRejected() throws Exception {
        persist(ProSubscriptionStatus.ACTIVE, ProSubscriptionSource.STRIPE);

        // Fermer une ligne Stripe ici désynchroniserait la base et Stripe : l'utilisateur
        // perdrait son accès tout en restant débité.
        mockMvc.perform(delete("/admin/users/{userId}/pro-grant", userId)
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not-an-admin-grant"));

        assertThat(subscriptionRepository.findByUserId(userId).orElseThrow().getStatus())
                .as("l'abonnement payant doit rester intact")
                .isEqualTo(ProSubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("révoquer sans aucun abonnement répond 404")
    void revokingWithoutSubscriptionReturns404() throws Exception {
        mockMvc.perform(delete("/admin/users/{userId}/pro-grant", userId)
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isNotFound());
    }
}
