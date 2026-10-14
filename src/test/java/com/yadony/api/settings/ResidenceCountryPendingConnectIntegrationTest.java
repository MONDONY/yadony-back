package com.yadony.api.settings;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.payments.ConnectAccountProvisioner;
import com.yadony.api.payments.StripeGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * De bout en bout : un utilisateur qui a commencé l'activation des paiements par carte sans
 * la finir peut corriger son pays de résidence. Le compte Connect en attente est détaché dans
 * la même transaction, fermé chez Stripe après commit, et un nouveau compte se crée ensuite
 * dans le bon pays. Un compte dont l'onboarding est terminé garde le pays verrouillé.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ResidenceCountryPendingConnectIntegrationTest {

    @Autowired MockMvc mockMvc;
    /**
     * {@code findByIdForUpdate} compile en {@code FOR NO KEY UPDATE}, refusé par H2 : le verrou
     * est remplacé par une lecture simple, sans effet sur ce qui est testé ici.
     */
    @MockitoSpyBean UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean StripeGateway stripeGateway;
    @MockitoBean ConnectAccountProvisioner connectAccountProvisioner;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        doAnswer(inv -> userRepository.findById(inv.getArgument(0)))
                .when(userRepository).findByIdForUpdate(any());
        user = new UserEntity();
        user.setFirebaseUid("uid-country-pending-" + UUID.randomUUID());
        user.setFirstName("Awa");
        user.setLastName("Tester");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        roles.add(Role.SENDER);
        user.setRoles(roles);
        user.setCountry("FR");
        user.setStripeAccountId("acct_pending_fr_" + UUID.randomUUID().toString().substring(0, 8));
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        user.setStripeAccountCreatedAt(Instant.now());
        user = userRepository.save(user);
    }

    @Test
    @DisplayName("Compte en attente : GET annonce le detachement, PUT pays 200, compte detache puis recree dans le nouveau pays")
    void pendingAccount_countryChange_detachesThenRecreates() throws Exception {
        String pendingAccountId = user.getStripeAccountId();

        mockMvc.perform(get("/users/me/business-preferences").with(authentication(asUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryLocked").value(false))
                .andExpect(jsonPath("$.countryChangeResetsPayoutSetup").value(true));

        mockMvc.perform(put("/users/me/business-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(countryBody("BE"))
                        .with(authentication(asUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.country").value("BE"))
                .andExpect(jsonPath("$.countryLocked").value(false))
                .andExpect(jsonPath("$.countryChangeResetsPayoutSetup").value(false));

        UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getCountry()).isEqualTo("BE");
        assertThat(reloaded.getStripeAccountId()).isNull();
        assertThat(reloaded.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.NOT_CREATED);
        assertThat(reloaded.getStripeAccountCreatedAt()).isNull();
        assertThat(userActions()).contains("RESIDENCE_COUNTRY_CHANGED", "STRIPE_ACCOUNT_DETACHED");
        // Fermeture chez Stripe, apres commit, au mieux.
        verify(stripeGateway).closeAccountV2(pendingAccountId);

        // Prochaine activation : un nouveau compte est cree, pour le nouveau pays.
        when(connectAccountProvisioner.provision(any())).thenReturn("acct_new_be");
        mockMvc.perform(post("/payments/connect/account").with(authentication(asUser())))
                .andExpect(status().is2xxSuccessful());

        verify(connectAccountProvisioner).provision(argThat(u -> "BE".equals(u.getCountry())));
        UserEntity recreated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(recreated.getStripeAccountId()).isEqualTo("acct_new_be");
        assertThat(recreated.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.PENDING_ONBOARDING);
    }

    @Test
    @DisplayName("Onboarding termine : PUT pays 422 country-locked et rien ne bouge")
    void completedAccount_countryChange_isRejected() throws Exception {
        user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        user.setStripeOnboardingCompletedAt(Instant.now());
        user = userRepository.save(user);
        String accountId = user.getStripeAccountId();

        mockMvc.perform(put("/users/me/business-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(countryBody("BE"))
                        .with(authentication(asUser())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getCountry()).isEqualTo("FR");
        assertThat(reloaded.getStripeAccountId()).isEqualTo(accountId);
        assertThat(reloaded.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.ONBOARDING_COMPLETE);
        assertThat(userActions()).doesNotContain("RESIDENCE_COUNTRY_CHANGED", "STRIPE_ACCOUNT_DETACHED");
        verify(stripeGateway, never()).closeAccountV2(any());
    }

    private List<String> userActions() {
        // Lecture de la seule colonne action : le payload JSONB ne se relit pas sous H2.
        return jdbcTemplate.queryForList(
                "SELECT action FROM audit_log WHERE entity_type = 'USER' AND entity_id = ?",
                String.class, user.getId());
    }

    private UsernamePasswordAuthenticationToken asUser() {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"),
                        new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static String countryBody(String country) {
        return "{\"weightUnit\":\"kg\",\"country\":\"" + country + "\",\"pickupRadiusKm\":10,"
                + "\"defaultPackageWeightKg\":23,\"minBidPriceEur\":0}";
    }
}
