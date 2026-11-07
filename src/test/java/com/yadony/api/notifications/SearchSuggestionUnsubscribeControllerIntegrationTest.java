package com.yadony.api.notifications;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lien de désinscription des e-mails de suggestion : public, sans jeton Firebase, le jeton signé fait foi. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SearchSuggestionUnsubscribeControllerIntegrationTest {

    private static final String URL = "/public/notifications/search-suggestions/unsubscribe";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired NotificationPrefsJpaRepository prefsRepository;
    @Autowired SearchSuggestionUnsubscribeTokens tokens;

    private UserEntity persistUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-unsub-" + UUID.randomUUID());
        u.setFirstName("Awa");
        u.setLastName("Koné");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private boolean suggestionsOn(UUID userId) {
        return prefsRepository.findById(userId).map(NotificationPrefsEntity::isSearchSuggestions).orElse(true);
    }

    @Test
    void get_onlyAsksForConfirmation_andChangesNothing() throws Exception {
        UserEntity user = persistUser();
        String token = tokens.issue(user.getId());

        mockMvc.perform(get(URL).param("t", token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Ne plus recevoir de suggestions ?")))
                .andExpect(content().string(containsString("method=\"post\"")))
                .andExpect(content().string(containsString("/public/notifications/search-suggestions/unsubscribe")))
                .andExpect(content().string(containsString("noindex")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        assertThat(suggestionsOn(user.getId())).isTrue();
    }

    @Test
    void post_turnsSuggestionsOff_likeTheOneClickUnsubscribeOfMailboxes() throws Exception {
        UserEntity user = persistUser();

        mockMvc.perform(post(URL).param("t", tokens.issue(user.getId()))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("C&#39;est noté")))
                .andExpect(content().string(not(containsString("method=\"post\""))));

        assertThat(suggestionsOn(user.getId())).isFalse();
    }

    @Test
    void invalidToken_showsTheExpiredPage_andChangesNothing() throws Exception {
        UserEntity user = persistUser();

        mockMvc.perform(get(URL).param("t", "faux.jeton"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lien expiré")));
        mockMvc.perform(post(URL))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lien expiré")));

        assertThat(suggestionsOn(user.getId())).isTrue();
    }
}
