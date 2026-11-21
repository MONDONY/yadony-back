package com.yadony.api.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.yadony.api.admin.account.AdminAuthService;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthControllerUsernameIT {

    private static final String UID = "uid-username-it";
    private static final String OTHER_UID = "uid-username-other";

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired ReleasedUsernameRepository releasedRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockitoBean AdminAuthService adminAuthService;
    @MockitoBean FirebaseAuth firebaseAuth;
    @MockitoBean FirebaseContactService firebaseContact;

    UserEntity me;
    UserEntity other;

    @BeforeEach
    void setUp() {
        releasedRepository.deleteAll();
        userRepository.deleteAll();
        me = seed(UID, "awa.old");
        other = seed(OTHER_UID, "moussa.k");
        when(adminAuthService.resolve(any())).thenReturn(Optional.empty());
        when(firebaseContact.getContact(any()))
                .thenReturn(new FirebaseContactService.Contact("+33612000009", null));
    }

    private UserEntity seed(String uid, String username) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setUsername(username);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(Set.of(Role.SENDER));
        u.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.save(u);
    }

    private UsernamePasswordAuthenticationToken auth(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority(role)));
    }

    private org.springframework.test.web.servlet.ResultActions putUsername(String value) throws Exception {
        return mvc.perform(put("/auth/me/username").with(authentication(auth(UID, "ROLE_SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + value + "\"}"));
    }

    @Test
    void put_valide_200_puisGetMeRenvoieLeNouvelIdentifiant() throws Exception {
        putUsername("Awa.New").andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("awa.new"));

        mvc.perform(get("/auth/me").with(authentication(auth(UID, "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("awa.new"));

        UserEntity reloaded = userRepository.findById(me.getId()).orElseThrow();
        assertThat(reloaded.getUsernameChangedAt()).isNotNull();
        assertThat(releasedRepository.existsByUsernameAndReleasedAtAfter("awa.old",
                OffsetDateTime.now().minusDays(1))).isTrue();
        // Lecture en SQL : le mappage JSON du payload n'est pas relisible sous H2.
        List<String> payloads = jdbc.queryForList(
                "SELECT CAST(payload AS VARCHAR) FROM audit_log WHERE entity_id = ? AND action = 'USERNAME_CHANGED'",
                String.class, me.getId());
        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0)).contains("awa.old").contains("awa.new");
    }

    @Test
    void put_dixJoursApresUnPrecedent_409TooSoonAvecNextChangeAt() throws Exception {
        me.setUsernameChangedAt(OffsetDateTime.now().minusDays(10));
        userRepository.save(me);

        putUsername("awa.new").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("username-change-too-soon")))
                .andExpect(jsonPath("$.nextChangeAt", notNullValue()));
    }

    @Test
    void put_pris_409AvecSuggestions() throws Exception {
        putUsername("Moussa.K").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("username-taken")))
                .andExpect(jsonPath("$.suggestions", org.hamcrest.Matchers.not(hasSize(0))));
    }

    @Test
    void put_invalide_422_reserve_422() throws Exception {
        putUsername("a b").andExpect(status().isUnprocessableEntity());
        putUsername("yadony.awa").andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/auth/me/username").with(authentication(auth(UID, "ROLE_SENDER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void put_invite_403() throws Exception {
        mvc.perform(put("/auth/me/username").with(authentication(auth(UID, "ROLE_GUEST")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"awa.new\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/auth/me/username/availability").param("value", "awa.new")
                        .with(authentication(auth(UID, "ROLE_GUEST"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void availability_reponsesSelonLeCas() throws Exception {
        var a = authentication(auth(UID, "ROLE_SENDER"));
        mvc.perform(get("/auth/me/username/availability").param("value", "awa.free").with(a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist());
        mvc.perform(get("/auth/me/username/availability").param("value", "moussa.k").with(a))
                .andExpect(jsonPath("$.reason").value("TAKEN"));
        mvc.perform(get("/auth/me/username/availability").param("value", "awa.old").with(a))
                .andExpect(jsonPath("$.reason").value("UNCHANGED"));
        mvc.perform(get("/auth/me/username/availability").param("value", "x").with(a))
                .andExpect(jsonPath("$.reason").value("INVALID_FORMAT"));
        releasedRepository.save(new ReleasedUsernameEntity("old.name", OffsetDateTime.now().minusDays(10),
                other.getId()));
        mvc.perform(get("/auth/me/username/availability").param("value", "old.name").with(a))
                .andExpect(jsonPath("$.reason").value("RECENTLY_RELEASED"));
    }
}
