package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Désignation des testeurs du mode recette (FLUTTER-FA/FB) de bout en bout, mode ouvert comme
 * en staging : super-administrateur seul, audit à chaque changement, champ {@code recetteMode}
 * de {@code /auth/me}. Le refus en environnement fermé est couvert par
 * {@link AdminRecetteTesterControllerTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "yadony.recette.enabled=true")
class AdminRecetteTesterControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean FirebaseContactService firebaseContact;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-recette-" + UUID.randomUUID());
        u.setFirstName("Testeur");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(Set.of(Role.SENDER, Role.TRAVELER));
        u.setTotalTrips(0);
        user = userRepository.save(u);
        lenient().when(firebaseContact.getContact(anyString()))
                .thenReturn(new FirebaseContactService.Contact("+33612000099", null));
    }

    private static UsernamePasswordAuthenticationToken admin(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-recette");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                role.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private UsernamePasswordAuthenticationToken self() {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder set(boolean enabled) {
        return put("/admin/users/{id}/recette-tester", user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}");
    }

    private List<String> auditActions() {
        return jdbcTemplate.queryForList(
                "SELECT action FROM audit_log WHERE entity_id = ? ORDER BY created_at", String.class, user.getId());
    }

    @Test
    void superAdmin_designeLeTesteur_meLeVoit_puisRevoque_auditUneFoisParChangement() throws Exception {
        mockMvc.perform(get("/auth/me").with(authentication(self())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recetteMode").value(false));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(set(true).with(authentication(admin(AdminRole.SUPER_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recetteTester").value(true))
                    .andExpect(jsonPath("$.recetteModeActive").value(true));
        }
        assertThat(userRepository.findById(user.getId()).orElseThrow().isRecetteTester()).isTrue();
        mockMvc.perform(get("/auth/me").with(authentication(self())))
                .andExpect(jsonPath("$.recetteMode").value(true));
        mockMvc.perform(get("/admin/users/{id}/recette-tester", user.getId())
                        .with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recetteTester").value(true));

        mockMvc.perform(set(false).with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recetteTester").value(false))
                .andExpect(jsonPath("$.recetteModeActive").value(false));

        assertThat(auditActions()).containsExactly("RECETTE_TESTER_GRANTED", "RECETTE_TESTER_REVOKED");
    }

    @Test
    void adminSansAdminManage_support_etUtilisateur_refuses() throws Exception {
        mockMvc.perform(set(true).with(authentication(admin(AdminRole.ADMIN))))
                .andExpect(status().isForbidden());
        mockMvc.perform(set(true).with(authentication(admin(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
        mockMvc.perform(set(true).with(authentication(self())))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(user.getId()).orElseThrow().isRecetteTester()).isFalse();
        assertThat(auditActions()).isEmpty();
    }

    @Test
    void utilisateurInconnu_404_corpsInvalide_422() throws Exception {
        mockMvc.perform(put("/admin/users/{id}/recette-tester", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")
                        .with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
        mockMvc.perform(put("/admin/users/{id}/recette-tester", user.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isUnprocessableEntity());
    }
}
