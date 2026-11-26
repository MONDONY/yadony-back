package com.yadony.api.admin.mobile;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminDeviceControllerIT {

    private static final UUID ADMIN_A = UUID.randomUUID();
    private static final UUID ADMIN_B = UUID.randomUUID();
    private static final String BODY =
            "{\"fcmToken\":\"%s\",\"deviceName\":\"iPhone d'Aboubakar\",\"platform\":\"ios\"}";

    @Autowired MockMvc mockMvc;
    @Autowired AdminDeviceRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    static UsernamePasswordAuthenticationToken admin(UUID id) {
        var principal = new AdminPrincipal(id, "admin@yadony.com", AdminRole.SUPER_ADMIN, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        AdminRole.SUPER_ADMIN.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    @Test
    @DisplayName("register crée l'appareil de l'admin authentifié")
    void register_createsDevice_forAuthenticatedAdmin() throws Exception {
        mockMvc.perform(post("/admin/mobile/devices").with(authentication(admin(ADMIN_A)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("tok-1")))
                .andExpect(status().isNoContent());

        var devices = repository.findActiveByAdminId(ADMIN_A);
        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).getFcmToken()).isEqualTo("tok-1");
        assertThat(devices.get(0).getPlatform()).isEqualTo("ios");
        assertThat(devices.get(0).getLastPushedAt()).isNotNull();
        assertThat(repository.findActive()).hasSize(1);
    }

    @Test
    @DisplayName("register est idempotent sur le jeton et reprend un appareil supprimé")
    void register_isIdempotent_onSameToken() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/admin/mobile/devices").with(authentication(admin(ADMIN_A)))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("tok-2")))
                    .andExpect(status().isNoContent());
        }
        assertThat(repository.findActive()).hasSize(1);

        mockMvc.perform(delete("/admin/mobile/devices").param("fcmToken", "tok-2")
                        .with(authentication(admin(ADMIN_A))))
                .andExpect(status().isNoContent());
        assertThat(repository.findActive()).isEmpty();

        mockMvc.perform(post("/admin/mobile/devices").with(authentication(admin(ADMIN_B)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("tok-2")))
                .andExpect(status().isNoContent());
        assertThat(repository.findActiveByAdminId(ADMIN_B)).hasSize(1);
        assertThat(repository.findActiveByAdminId(ADMIN_A)).isEmpty();
    }

    @Test
    @DisplayName("register refuse un corps invalide (422, convention du projet)")
    void register_returns422_onBlankToken() throws Exception {
        mockMvc.perform(post("/admin/mobile/devices").with(authentication(admin(ADMIN_A)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("register refuse un appelant sans rôle admin (403)")
    void register_returns403_withoutAdminRole() throws Exception {
        var user = new UsernamePasswordAuthenticationToken("user", null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
        mockMvc.perform(post("/admin/mobile/devices").with(authentication(user))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("tok-3")))
                .andExpect(status().isForbidden());
        assertThat(repository.findActive()).isEmpty();
    }

    @Test
    @DisplayName("unregister ne supprime (soft) que l'appareil de l'admin appelant")
    void unregister_softDeletesOnlyOwnDevice() throws Exception {
        mockMvc.perform(post("/admin/mobile/devices").with(authentication(admin(ADMIN_A)))
                .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("tok-a")));
        mockMvc.perform(post("/admin/mobile/devices").with(authentication(admin(ADMIN_B)))
                .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("tok-b")));

        // B tente de supprimer l'appareil de A : sans effet
        mockMvc.perform(delete("/admin/mobile/devices").param("fcmToken", "tok-a")
                        .with(authentication(admin(ADMIN_B))))
                .andExpect(status().isNoContent());
        assertThat(repository.findActiveByAdminId(ADMIN_A)).hasSize(1);

        mockMvc.perform(delete("/admin/mobile/devices").param("fcmToken", "tok-a")
                        .with(authentication(admin(ADMIN_A))))
                .andExpect(status().isNoContent());
        assertThat(repository.findActiveByAdminId(ADMIN_A)).isEmpty();
        assertThat(repository.findActiveByAdminId(ADMIN_B)).hasSize(1);
    }
}
