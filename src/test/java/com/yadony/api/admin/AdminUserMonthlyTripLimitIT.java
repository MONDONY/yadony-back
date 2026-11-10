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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
class AdminUserMonthlyTripLimitIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @MockBean FirebaseContactService firebaseContact;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        when(firebaseContact.getContact(any())).thenReturn(FirebaseContactService.Contact.EMPTY);
        user = new UserEntity();
        user.setFirebaseUid("uid-mtl-" + UUID.randomUUID());
        user.setUsername("mtl-" + UUID.randomUUID().toString().substring(0, 8));
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(new java.util.HashSet<>(Set.of(Role.TRAVELER)));
        user = userRepository.saveAndFlush(user);
    }

    private UsernamePasswordAuthenticationToken adminAuth(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-mtl");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                role.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private Integer storedLimit() {
        return userRepository.findById(user.getId()).orElseThrow().getMonthlyTripLimit();
    }

    @Test
    void adminFixeLaLimite_etLaFicheLaRenvoieAvecLeCompteurDuMois() throws Exception {
        mockMvc.perform(put("/admin/users/{id}/monthly-trip-limit", user.getId())
                        .with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyTripLimit").value(3))
                .andExpect(jsonPath("$.monthlyTripsUsed").value(0));
        assertThat(storedLimit()).isEqualTo(3);

        mockMvc.perform(get("/admin/users/{id}", user.getId()).with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyTripLimit").value(3))
                .andExpect(jsonPath("$.monthlyTripsUsed").value(0));
    }

    @Test
    void limiteNulle_retireLaLimite() throws Exception {
        user.setMonthlyTripLimit(5);
        userRepository.saveAndFlush(user);

        mockMvc.perform(put("/admin/users/{id}/monthly-trip-limit", user.getId())
                        .with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyTripLimit").doesNotExist());
        assertThat(storedLimit()).isNull();
    }

    @Test
    void horsBornes_422_rienEcrit() throws Exception {
        for (String body : new String[]{"{\"limit\":0}", "{\"limit\":101}"}) {
            mockMvc.perform(put("/admin/users/{id}/monthly-trip-limit", user.getId())
                            .with(authentication(adminAuth(AdminRole.ADMIN)))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity());
        }
        assertThat(storedLimit()).isNull();
    }

    @Test
    void supportSansLeDroit_403() throws Exception {
        mockMvc.perform(put("/admin/users/{id}/monthly-trip-limit", user.getId())
                        .with(authentication(adminAuth(AdminRole.SUPPORT)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":3}"))
                .andExpect(status().isForbidden());
        assertThat(storedLimit()).isNull();
    }

    @Test
    void utilisateurInconnu_404() throws Exception {
        mockMvc.perform(put("/admin/users/{id}/monthly-trip-limit", UUID.randomUUID())
                        .with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":3}"))
                .andExpect(status().isNotFound());
    }
}
