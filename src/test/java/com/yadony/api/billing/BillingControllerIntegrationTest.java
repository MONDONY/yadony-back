package com.yadony.api.billing;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("BillingController")
class BillingControllerIntegrationTest {

    private static final String FIREBASE_UID = "uid-billing-ctrl-001";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProSubscriptionRepository subscriptionRepository;

    @MockitoBean FirebaseContactService firebaseContact;

    private UUID userId;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        UserEntity user = new UserEntity();
        user.setFirebaseUid(FIREBASE_UID);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER));
        user.setCountry("FR");
        userId = userRepository.save(user).getId();

        org.mockito.Mockito.lenient().when(firebaseContact.getContact(FIREBASE_UID))
                .thenReturn(new FirebaseContactService.Contact("+33612000009", null));
    }

    private UsernamePasswordAuthenticationToken authenticated() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @Test
    @DisplayName("sans abonnement, GET /billing/subscription répond NONE et inactif")
    void subscriptionAbsentReturnsNone() throws Exception {
        mockMvc.perform(get("/billing/subscription").with(authentication(authenticated())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.status").value("NONE"));
    }

    @Test
    @DisplayName("avec une grâce en cours, GET /billing/subscription l'expose comme active")
    void subscriptionInGraceIsActive() throws Exception {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setUserId(userId);
        sub.setStatus(ProSubscriptionStatus.LEGACY_GRACE);
        sub.setSource(ProSubscriptionSource.LEGACY_FREE);
        sub.setGraceExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);

        mockMvc.perform(get("/billing/subscription").with(authentication(authenticated())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("LEGACY_GRACE"))
                .andExpect(jsonPath("$.graceExpiresAt").exists());
    }

    @Test
    @DisplayName("sans authentification, les endpoints d'abonnement sont refusés")
    void endpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/billing/subscription"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/billing/portal-session"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("le webhook est public mais refuse une signature invalide")
    void webhookIsPublicButRejectsBadSignature() throws Exception {
        mockMvc.perform(post("/billing/webhook")
                        .header("Stripe-Signature", "t=1,v1=invalide")
                        .content("{\"id\":\"evt_x\",\"type\":\"invoice.paid\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sans client Stripe rattaché, le portail répond 404")
    void portalWithoutCustomerReturns404() throws Exception {
        mockMvc.perform(post("/billing/portal-session").with(authentication(authenticated())))
                .andExpect(status().isNotFound());
    }
}
