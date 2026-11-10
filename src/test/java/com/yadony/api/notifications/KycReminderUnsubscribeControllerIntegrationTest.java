package com.yadony.api.notifications;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lien de désinscription des relances de vérification : public, sans jeton Firebase, le jeton signé fait foi. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class KycReminderUnsubscribeControllerIntegrationTest {

    private static final String URL = "/public/notifications/kyc-reminders/unsubscribe";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired KycReminderUnsubscribeTokens tokens;
    @SpyBean AuditService auditService;

    private UserEntity persistUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-kyc-unsub-" + UUID.randomUUID());
        u.setFirstName("Awa");
        u.setLastName("Koné");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private Instant optedOutAt(UUID id) {
        return userRepository.findById(id).orElseThrow().getKycRemindersOptedOutAt();
    }

    @Test
    void get_afficheConfirmation_sansRienEcrire() throws Exception {
        UserEntity user = persistUser();

        mockMvc.perform(get(URL).param("t", tokens.issue(user.getId())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Ne plus recevoir les rappels de vérification ?")))
                .andExpect(content().string(containsString("method=\"post\"")))
                .andExpect(content().string(containsString("/public/notifications/kyc-reminders/unsubscribe")))
                .andExpect(content().string(containsString("noindex")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        assertThat(optedOutAt(user.getId())).isNull();
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void post_desinscritEtAudite() throws Exception {
        UserEntity user = persistUser();

        mockMvc.perform(post(URL).param("t", tokens.issue(user.getId()))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("C&#39;est noté")))
                .andExpect(content().string(not(containsString("method=\"post\""))));

        assertThat(optedOutAt(user.getId())).isNotNull();
        verify(auditService).log("USER", user.getId(), "KYC_REMINDERS_UNSUBSCRIBED", user.getId(), Map.of());
    }

    @Test
    void post_deuxFois_garde_la_premiere_date() throws Exception {
        UserEntity user = persistUser();
        String token = tokens.issue(user.getId());

        mockMvc.perform(post(URL).param("t", token)).andExpect(status().isOk());
        Instant first = optedOutAt(user.getId());
        mockMvc.perform(post(URL).param("t", token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("C&#39;est noté")));

        assertThat(optedOutAt(user.getId())).isEqualTo(first);
        verify(auditService, times(1)).log("USER", user.getId(), "KYC_REMINDERS_UNSUBSCRIBED", user.getId(), Map.of());
    }

    @Test
    void jetonInvalide_pageExpired_etRienEcrit() throws Exception {
        UserEntity user = persistUser();

        mockMvc.perform(get(URL).param("t", "faux.jeton"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lien expiré")));
        mockMvc.perform(post(URL))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lien expiré")));
        // Un jeton de suggestion ne vaut pas désinscription des relances.
        String suggestionToken = new SearchSuggestionUnsubscribeTokens("une-cle", java.time.Clock.systemUTC())
                .issue(user.getId());
        mockMvc.perform(post(URL).param("t", suggestionToken))
                .andExpect(content().string(containsString("Lien expiré")));

        assertThat(optedOutAt(user.getId())).isNull();
    }

    @Test
    void routePublique_sansAuthentification() throws Exception {
        // Aucun en-tête Authorization : ni 401 ni 403.
        mockMvc.perform(get(URL)).andExpect(status().isOk());
        mockMvc.perform(post(URL)).andExpect(status().isOk());
    }
}
