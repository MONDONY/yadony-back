package com.yadony.api.payments;

import com.stripe.model.AccountLink;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bouton de l'e-mail de relance : public, sans jeton Firebase, le jeton signé fait foi. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StripeOnboardingResumeControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired StripeOnboardingResumeTokens tokens;
    @MockitoBean StripeGateway stripeGateway;

    private UserEntity persistTraveler(StripeAccountStatus stripeStatus) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-resume-" + UUID.randomUUID());
        u.setFirstName("Ibrahima");
        u.setLastName("Sow");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        u.setStripeAccountId("acct_" + UUID.randomUUID().toString().substring(0, 8));
        u.setStripeAccountStatus(stripeStatus);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    @Test
    void validToken_redirectsToStripe_andComesBackHereWhenTheLinkExpires() throws Exception {
        UserEntity traveler = persistTraveler(StripeAccountStatus.PENDING_ONBOARDING);
        AccountLink link = new AccountLink();
        link.setUrl("https://connect.stripe.com/setup/e/acct/abc");
        when(stripeGateway.createAccountLink(org.mockito.ArgumentMatchers.any())).thenReturn(link);
        String token = tokens.issue(traveler.getId());

        mockMvc.perform(get("/payments/onboarding/resume").param("t", token))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://connect.stripe.com/setup/e/acct/abc"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        verify(stripeGateway).createAccountLink(argThat(p ->
                p.getAccount().equals(traveler.getStripeAccountId())
                        && p.getRefreshUrl().endsWith("/payments/onboarding/resume?t=" + token)
                        && p.getReturnUrl().endsWith("/payments/onboarding/resume/done")));
    }

    @Test
    void completedOnboarding_showsThatNothingIsLeft() throws Exception {
        UserEntity traveler = persistTraveler(StripeAccountStatus.ONBOARDING_COMPLETE);

        mockMvc.perform(get("/payments/onboarding/resume").param("t", tokens.issue(traveler.getId())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Inscription déjà terminée")))
                .andExpect(content().string(containsString("noindex")));
        verifyNoInteractions(stripeGateway);
    }

    @Test
    void accountThatCannotResume_sendsToTheApp() throws Exception {
        UserEntity traveler = persistTraveler(StripeAccountStatus.DISABLED);

        mockMvc.perform(get("/payments/onboarding/resume").param("t", tokens.issue(traveler.getId())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Votre inscription ne peut pas reprendre depuis ce lien")));
        verifyNoInteractions(stripeGateway);
    }

    @Test
    void forgedOrMissingToken_showsExpiredPage() throws Exception {
        mockMvc.perform(get("/payments/onboarding/resume").param("t", "faux.jeton"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lien expiré")));
        mockMvc.perform(get("/payments/onboarding/resume"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lien expiré")));
        verifyNoInteractions(stripeGateway);
    }

    @Test
    void donePage_isPublic_andFollowsTheBrowserLanguage() throws Exception {
        mockMvc.perform(get("/payments/onboarding/resume/done"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Informations envoyées")))
                .andExpect(content().string(containsString("https://yadony.com")));
        mockMvc.perform(get("/payments/onboarding/resume/done").header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Details sent")));
    }
}
