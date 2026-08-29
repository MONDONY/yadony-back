package com.yadony.api.billing;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    /**
     * Le contrôleur n'a qu'à résoudre l'utilisateur authentifié et déléguer :
     * il n'a pas à appeler Stripe pour être testé. Les chemins nominaux et
     * les gardes métier du service sont couverts séparément par
     * {@code StripeBillingServiceTest}.
     */
    @MockitoBean StripeBillingService stripeBillingService;

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

    /**
     * Le contrat que lit le portail PRO pour annoncer « 7 jours offerts ». Il est vérifié
     * ici, sur le JSON réel, et pas seulement sur la politique : c'est la sérialisation qui
     * arrive au client, et un champ oublié dans le DTO ne se verrait nulle part ailleurs.
     */
    @Test
    @DisplayName("un voyageur ayant déjà roulé se voit annoncer l'essai")
    void subscriptionExposesTrialEligibilityForAnExperiencedTraveler() throws Exception {
        UserEntity user = userRepository.findById(userId).orElseThrow();
        user.setTotalTrips(1);
        userRepository.save(user);

        mockMvc.perform(get("/billing/subscription").with(authentication(authenticated())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialEligible").value(true))
                .andExpect(jsonPath("$.trialDays").value(7));
    }

    @Test
    @DisplayName("sans trajet réalisé, aucun essai n'est annoncé")
    void subscriptionAnnouncesNoTrialWithoutACompletedTrip() throws Exception {
        mockMvc.perform(get("/billing/subscription").with(authentication(authenticated())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialEligible").value(false))
                .andExpect(jsonPath("$.trialDays").doesNotExist());
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
        // Content-Type explicite : sans lui, Spring rejette la requête avant
        // même d'atteindre le contrôleur (échec de résolution du
        // @RequestBody String), et le 400 obtenu ne prouverait rien du rejet
        // de signature par StripeWebhookIngestService.
        mockMvc.perform(post("/billing/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=invalide")
                        .content("{\"id\":\"evt_x\",\"type\":\"invoice.paid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("invalid-webhook-signature"));
    }

    @Test
    @DisplayName("jeton valide mais compte disparu : firebaseUid inconnu répond 401 en ProblemDetail")
    void unknownFirebaseUidReturnsProblemDetail401() throws Exception {
        // Protège un jeton Firebase valide dont le compte a été supprimé
        // entre-temps : currentUserId() ne trouve personne et doit refuser
        // proprement plutôt que de lever une NoSuchElementException brute.
        UsernamePasswordAuthenticationToken unknownUser = new UsernamePasswordAuthenticationToken(
                "uid-does-not-exist-in-db", null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));

        mockMvc.perform(get("/billing/subscription").with(authentication(unknownUser)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("unknown-user"));
    }

    @Test
    @DisplayName("sans client Stripe rattaché, le portail répond 404")
    void portalWithoutCustomerReturns404() throws Exception {
        when(stripeBillingService.createPortalSession(userId))
                .thenThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "no-stripe-customer", "No Billing Account",
                        "Aucun abonnement payant n'est rattaché à ce compte."));

        mockMvc.perform(post("/billing/portal-session").with(authentication(authenticated())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /billing/checkout-session délègue au service avec l'utilisateur authentifié et répond 200")
    void checkoutSessionDelegatesToServiceWithAuthenticatedUser() throws Exception {
        when(stripeBillingService.createCheckoutSession(any(), any()))
                .thenReturn("https://checkout.stripe.com/pay/cs_test_1");

        mockMvc.perform(post("/billing/checkout-session").with(authentication(authenticated())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/pay/cs_test_1"));

        ArgumentCaptor<UUID> userIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<BillingCycle> cycleCaptor = ArgumentCaptor.forClass(BillingCycle.class);
        verify(stripeBillingService).createCheckoutSession(userIdCaptor.capture(), cycleCaptor.capture());

        // La garantie centrale : on ne peut jamais souscrire pour autrui,
        // ni sur un autre cycle que celui demandé.
        assertUserIdMatchesAuthenticatedUser(userIdCaptor.getValue());
        org.assertj.core.api.Assertions.assertThat(cycleCaptor.getValue()).isEqualTo(BillingCycle.MONTHLY);
    }

    @Test
    @DisplayName("POST /billing/checkout-session?cycle=YEARLY transmet bien le cycle annuel")
    void checkoutSessionPassesYearlyCycle() throws Exception {
        when(stripeBillingService.createCheckoutSession(any(), any()))
                .thenReturn("https://checkout.stripe.com/pay/cs_test_yearly");

        mockMvc.perform(post("/billing/checkout-session")
                        .param("cycle", "YEARLY")
                        .with(authentication(authenticated())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/pay/cs_test_yearly"));

        verify(stripeBillingService).createCheckoutSession(eq(userId), eq(BillingCycle.YEARLY));
    }

    @Test
    @DisplayName("POST /billing/portal-session délègue au service et répond 200 avec l'URL")
    void portalSessionDelegatesToServiceAndReturnsUrl() throws Exception {
        when(stripeBillingService.createPortalSession(userId))
                .thenReturn("https://billing.stripe.com/session/bps_test_1");

        mockMvc.perform(post("/billing/portal-session").with(authentication(authenticated())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://billing.stripe.com/session/bps_test_1"));

        verify(stripeBillingService).createPortalSession(userId);
    }

    @Test
    @DisplayName("abonnement déjà actif : la 409 métier du service est renvoyée en ProblemDetail, pas en erreur brute")
    void checkoutSessionAlreadyActiveReturnsProblemDetail409() throws Exception {
        when(stripeBillingService.createCheckoutSession(any(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                        "subscription-already-active", "Already Subscribed",
                        "Vous avez déjà un abonnement PRO en cours."));

        mockMvc.perform(post("/billing/checkout-session").with(authentication(authenticated())))
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("subscription-already-active"));
    }

    private void assertUserIdMatchesAuthenticatedUser(UUID capturedUserId) {
        org.assertj.core.api.Assertions.assertThat(capturedUserId).isEqualTo(userId);
    }
}
