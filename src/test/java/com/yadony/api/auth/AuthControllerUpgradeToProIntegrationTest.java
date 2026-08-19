package com.yadony.api.auth;

import com.yadony.api.auth.dto.UpgradeToProRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code POST /auth/me/upgrade-to-pro}.
 *
 * <p>Uses {@code @ActiveProfiles("test")} to leverage the H2 in-memory DB.
 * Each test seeds a user directly via {@link UserRepository}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("POST /auth/me/upgrade-to-pro — integration tests")
class AuthControllerUpgradeToProIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;

    /** Téléphone et email ne sont plus en base : c'est Firebase qui les sert. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    FirebaseContactService firebaseContact;

    private static final String FIREBASE_UID = "uid-pro-it-001";
    private static final String FIREBASE_UID_WITH_STRIPE = "uid-pro-it-002";

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        // Seed a plain user (no Stripe account)
        UserEntity plainUser = new UserEntity();
        plainUser.setFirebaseUid(FIREBASE_UID);
        plainUser.setStatus(UserStatus.ACTIVE);
        plainUser.setKycStatus(KycStatus.PENDING);
        plainUser.setRoles(Set.of(Role.SENDER, Role.TRAVELER));
        plainUser.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        // Le défaut "FR" en dur a été retiré de UserEntity (V225, le pays est
        // désormais une donnée saisie) : ce test vérifie explicitement le mapping
        // du champ country dans la réponse, donc la fixture doit le renseigner.
        plainUser.setCountry("FR");
        userRepository.save(plainUser);

        // Seed a user who already has a Stripe account
        UserEntity stripeUser = new UserEntity();
        stripeUser.setFirebaseUid(FIREBASE_UID_WITH_STRIPE);
        stripeUser.setStatus(UserStatus.ACTIVE);
        stripeUser.setKycStatus(KycStatus.PENDING);
        stripeUser.setRoles(Set.of(Role.TRAVELER));
        stripeUser.setStripeAccountId("acct_existing_123");
        stripeUser.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        userRepository.save(stripeUser);

        org.mockito.Mockito.lenient().when(firebaseContact.getContact(FIREBASE_UID))
                .thenReturn(new FirebaseContactService.Contact("+33612000001", null));
        org.mockito.Mockito.lenient().when(firebaseContact.getContact(FIREBASE_UID_WITH_STRIPE))
                .thenReturn(new FirebaseContactService.Contact("+33612000002", null));
    }

    private UsernamePasswordAuthenticationToken authenticatedAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"),
                        new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @Test
    @DisplayName("200 OK with valid body → isProAccount=true, stripeAccountStatus=NOT_CREATED, country=FR in response")
    void upgradeToPro_success_returns200() throws Exception {
        UpgradeToProRequest request = new UpgradeToProRequest("Yadony SARL", "12345678901234");

        mockMvc.perform(post("/auth/me/upgrade-to-pro")
                        .with(authentication(authenticatedAs(FIREBASE_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.phoneNumber").value("+33612000001"))
                .andExpect(jsonPath("$.isProAccount").value(true))
                .andExpect(jsonPath("$.stripeAccountStatus").value("NOT_CREATED"))
                .andExpect(jsonPath("$.country").value("FR"));
    }

    @Test
    @DisplayName("200 OK when user already has a Stripe Connect account → compte pro indépendant de Stripe")
    void upgradeToPro_withStripeAccount_returns200() throws Exception {
        UpgradeToProRequest request = new UpgradeToProRequest("Yadony SARL", "12345678901234");

        mockMvc.perform(post("/auth/me/upgrade-to-pro")
                        .with(authentication(authenticatedAs(FIREBASE_UID_WITH_STRIPE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isProAccount").value(true))
                .andExpect(jsonPath("$.stripeAccountStatus").value("PENDING_ONBOARDING"));
    }

    @Test
    @DisplayName("401 Unauthorized when no authentication provided")
    void upgradeToPro_noAuth_returns401() throws Exception {
        UpgradeToProRequest request = new UpgradeToProRequest("Yadony SARL", "12345678901234");

        mockMvc.perform(post("/auth/me/upgrade-to-pro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("422 Unprocessable when siret is invalid (not 14 digits)")
    void upgradeToPro_invalidSiret_returns422() throws Exception {
        UpgradeToProRequest request = new UpgradeToProRequest("Yadony SARL", "1234567"); // too short

        mockMvc.perform(post("/auth/me/upgrade-to-pro")
                        .with(authentication(authenticatedAs(FIREBASE_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid-siret"));
    }
}
