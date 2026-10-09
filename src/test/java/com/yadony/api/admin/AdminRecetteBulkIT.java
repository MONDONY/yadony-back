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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Désignation des testeurs en masse et disponibilité du mode, mode ouvert comme en staging.
 * Le refus en environnement fermé est couvert par {@link AdminRecetteClosedIT}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "yadony.recette.enabled=true")
class AdminRecetteBulkIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean FirebaseContactService firebaseContact;

    private UserEntity alice;
    private UserEntity bob;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(newUser("Alice"));
        bob = userRepository.save(newUser("Bob"));
        lenient().when(firebaseContact.getContacts(any())).thenReturn(Map.of());
    }

    private static UserEntity newUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-bulk-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Recette" + UUID.randomUUID().toString().substring(0, 8));
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(Set.of(Role.SENDER));
        u.setTotalTrips(0);
        return u;
    }

    static UsernamePasswordAuthenticationToken admin(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-bulk");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                role.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    static MockHttpServletRequestBuilder bulk(List<UUID> ids, boolean enabled) {
        String json = ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",", "[", "]"));
        return put("/admin/users/recette-tester")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIds\":" + json + ",\"enabled\":" + enabled + "}");
    }

    private List<String> auditActions(UUID userId) {
        return jdbcTemplate.queryForList(
                "SELECT action FROM audit_log WHERE entity_id = ? ORDER BY created_at", String.class, userId);
    }

    @Test
    void statut_ouvert_lisibleParTousLesAdmins() throws Exception {
        for (AdminRole role : AdminRole.values()) {
            mockMvc.perform(get("/admin/recette/status").with(authentication(admin(role))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));
        }
    }

    @Test
    void superAdmin_activeEnMasse_puisDesactive_auditUneFoisParChangement() throws Exception {
        UUID missing = UUID.randomUUID();
        mockMvc.perform(bulk(List.of(alice.getId(), bob.getId(), missing), true)
                        .with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.unchanged").value(0))
                .andExpect(jsonPath("$.notFound[0]").value(missing.toString()));

        // Rejoué : rien ne change, aucune entrée d'audit supplémentaire
        mockMvc.perform(bulk(List.of(alice.getId(), bob.getId()), true)
                        .with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.unchanged").value(2));

        assertThat(userRepository.findById(alice.getId()).orElseThrow().isRecetteTester()).isTrue();

        // La liste expose le drapeau et filtre sur lui
        mockMvc.perform(get("/admin/users").param("query", alice.getLastName()).param("recetteTester", "true")
                        .with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(alice.getId().toString()))
                .andExpect(jsonPath("$.content[0].recetteTester").value(true));
        mockMvc.perform(get("/admin/users").param("query", alice.getLastName()).param("recetteTester", "false")
                        .with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(bulk(List.of(alice.getId()), false)
                        .with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(jsonPath("$.updated").value(1));

        assertThat(auditActions(alice.getId()))
                .containsExactly("RECETTE_TESTER_GRANTED", "RECETTE_TESTER_REVOKED");
        assertThat(auditActions(bob.getId())).containsExactly("RECETTE_TESTER_GRANTED");
    }

    @Test
    void sansAdminManage_refuse403() throws Exception {
        for (AdminRole role : List.of(AdminRole.ADMIN, AdminRole.SUPPORT)) {
            mockMvc.perform(bulk(List.of(alice.getId()), true).with(authentication(admin(role))))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(bulk(List.of(alice.getId()), true).with(authentication(
                        new UsernamePasswordAuthenticationToken(alice.getFirebaseUid(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_SENDER"))))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/recette/status").with(authentication(
                        new UsernamePasswordAuthenticationToken(alice.getFirebaseUid(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_SENDER"))))))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(alice.getId()).orElseThrow().isRecetteTester()).isFalse();
        assertThat(auditActions(alice.getId())).isEmpty();
    }

    @Test
    void lotVide_tropGrand_ouSansEnabled_refuse422() throws Exception {
        mockMvc.perform(bulk(List.of(), true).with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isUnprocessableEntity());
        List<UUID> tooMany = IntStream.range(0, AdminRecetteTesterService.MAX_BULK + 1)
                .mapToObj(i -> UUID.randomUUID()).toList();
        mockMvc.perform(bulk(tooMany, true).with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/admin/users/recette-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[\"" + alice.getId() + "\"]}")
                        .with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isUnprocessableEntity());
    }
}
