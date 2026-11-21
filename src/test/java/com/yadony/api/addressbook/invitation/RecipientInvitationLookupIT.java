package com.yadony.api.addressbook.invitation;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RecipientInvitationLookupIT {

    private static final String ME_UID = "firebase-lookup-me";
    private static final String AWA_UID = "firebase-lookup-awa";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private UserEntity me;
    private UserEntity awa;

    @BeforeEach
    void seed() {
        purge();
        me = persist(ME_UID, "Moussa", "Traore", "lkp.moussa");
        awa = persist(AWA_UID, "awa", "Diallo", "lkp.awa");
    }

    @AfterEach
    void purge() {
        // Suppression physique, par compte et par identifiant (préfixe « lkp. » réservé à cette classe) : un
        // compte supprimé en douceur garderait son identifiant (unique) et la base de test est partagée.
        String ids = "SELECT id FROM users WHERE firebase_uid IN (?, ?) OR lower(username) LIKE 'lkp.%'";
        jdbc.update("DELETE FROM user_blocks WHERE blocker_id IN (" + ids + ") OR blocked_id IN (" + ids + ")",
                ME_UID, AWA_UID, ME_UID, AWA_UID);
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (" + ids + ")", ME_UID, AWA_UID);
        jdbc.update("DELETE FROM users WHERE firebase_uid IN (?, ?) OR lower(username) LIKE 'lkp.%'", ME_UID, AWA_UID);
    }

    private UserEntity persist(String uid, String first, String last, String username) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setFirstName(first);
        u.setLastName(last);
        u.setUsername(username);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new HashSet<>(List.of(Role.SENDER)));
        return userRepository.save(u);
    }

    private ResultActions lookup(String username) throws Exception {
        return mockMvc.perform(get("/recipient-invitations/lookup").param("username", username)
                .with(authentication(new UsernamePasswordAuthenticationToken(ME_UID, null,
                        List.of(new SimpleGrantedAuthority("ROLE_SENDER"))))));
    }

    @Test
    void foundReturnsOnlyUsernameAndInitial() throws Exception {
        lookup("lkp.awa").andExpect(status().isOk())
                .andExpect(content().json("{\"username\":\"lkp.awa\",\"initial\":\"A\"}", true))
                .andExpect(jsonPath("$.phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.lastName").doesNotExist())
                .andExpect(jsonPath("$.firstName").doesNotExist());
    }

    @Test
    void mixedCaseFindsSameAccount() throws Exception {
        lookup("LKP.Awa").andExpect(status().isOk()).andExpect(jsonPath("$.username").value("lkp.awa"));
    }

    @Test
    void unknownIs404() throws Exception {
        lookup("personne.ici").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Utilisateur introuvable"));
    }

    @Test
    void invalidFormatIs404() throws Exception {
        lookup("a b!").andExpect(status().isNotFound());
    }

    @Test
    void softDeletedAccountIs404() throws Exception {
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", awa.getId());
        lookup("lkp.awa").andExpect(status().isNotFound());
    }

    @Test
    void userWhoBlockedCallerIs404() throws Exception {
        jdbc.update("INSERT INTO user_blocks (id, blocker_id, blocked_id, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), awa.getId(), me.getId());
        lookup("lkp.awa").andExpect(status().isNotFound());
    }

    @Test
    void selfIs422() throws Exception {
        lookup("lkp.moussa").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://yadony.app/errors/recipient-invitation-self"));
    }

    @Test
    void thirtyFirstCallWithinTheHourIs429EvenForUnknown() throws Exception {
        for (int i = 0; i < 30; i++) {
            lookup("inconnu.numero").andExpect(status().isNotFound());
        }
        lookup("lkp.awa").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("https://yadony.app/errors/recipient-lookup-quota"));
    }

    @Test
    void suspendedAccountIs404() throws Exception {
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", awa.getId());
        lookup("lkp.awa").andExpect(status().isNotFound());
    }

    @Test
    void invalidFormatDoesNotConsumeTheQuota() throws Exception {
        for (int i = 0; i < 40; i++) {
            lookup("a b!").andExpect(status().isNotFound());
        }
        lookup("lkp.awa").andExpect(status().isOk());
    }
}
