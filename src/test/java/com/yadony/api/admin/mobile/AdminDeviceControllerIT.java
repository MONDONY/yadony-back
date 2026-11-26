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

import java.time.Instant;
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
        assertThat(repository.findAnyByFcmToken("tok-a")).isPresent()
                .get().extracting(AdminDeviceEntity::getDeletedAt).isNotNull();
    }

    @Test
    @DisplayName("unregister d'un jeton inconnu répond 204")
    void unregister_unknownToken_returns204() throws Exception {
        mockMvc.perform(delete("/admin/mobile/devices").param("fcmToken", "inconnu")
                        .with(authentication(admin(ADMIN_A))))
                .andExpect(status().isNoContent());
    }

    private void expect422(String body) throws Exception {
        mockMvc.perform(post("/admin/mobile/devices").with(authentication(admin(ADMIN_A)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
        assertThat(repository.findActive()).isEmpty();
    }

    @Test
    void register_returns422_whenFcmTokenTooLong() throws Exception {
        expect422(BODY.formatted("x".repeat(513)));
    }

    @Test
    void register_returns422_whenDeviceNameTooLong() throws Exception {
        expect422("{\"fcmToken\":\"t\",\"deviceName\":\"" + "n".repeat(256) + "\",\"platform\":\"ios\"}");
    }

    @Test
    void register_returns422_whenPlatformUnknown() throws Exception {
        expect422("{\"fcmToken\":\"t\",\"deviceName\":\"d\",\"platform\":\"symbian\"}");
    }

    // ── Curseur à la reprise d'un jeton (F3) ─────────────────────────────────

    /**
     * Curseur vieilli de onze mois. Sous H2, un {@code Instant} relu peut être décalé du fuseau de
     * la JVM (heure de Paris en local, UTC en CI) : les assertions comparent des valeurs relues
     * entre elles, ou « maintenant » à un jour près, jamais une valeur écrite à sa relecture.
     */
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");

    private void register(UUID adminId, String token) throws Exception {
        mockMvc.perform(post("/admin/mobile/devices").with(authentication(admin(adminId)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(token)))
                .andExpect(status().isNoContent());
    }

    private void ageCursor(String token) {
        AdminDeviceEntity d = repository.findAnyByFcmToken(token).orElseThrow();
        d.setLastPushedAt(OLD);
        repository.save(d);
    }

    private Instant cursorOf(String token) {
        return repository.findAnyByFcmToken(token).orElseThrow().getLastPushedAt();
    }

    private static Instant aDayAgo() {
        return Instant.now().minus(java.time.Duration.ofDays(1));
    }

    @Test
    @DisplayName("un jeton supprimé puis ré-enregistré repart de maintenant, pas de son vieux curseur")
    void register_revivedDevice_resetsCursorToNow() throws Exception {
        register(ADMIN_A, "tok-revive");
        ageCursor("tok-revive");
        assertThat(cursorOf("tok-revive")).isBefore(aDayAgo());
        mockMvc.perform(delete("/admin/mobile/devices").param("fcmToken", "tok-revive")
                        .with(authentication(admin(ADMIN_A))))
                .andExpect(status().isNoContent());

        register(ADMIN_A, "tok-revive");

        assertThat(cursorOf("tok-revive")).isAfter(aDayAgo());
        assertThat(repository.findActiveByAdminId(ADMIN_A)).hasSize(1);
    }

    @Test
    @DisplayName("un jeton repris par un autre admin repart de maintenant")
    void register_reassignedDevice_resetsCursorToNow() throws Exception {
        register(ADMIN_A, "tok-move");
        ageCursor("tok-move");
        assertThat(cursorOf("tok-move")).isBefore(aDayAgo());

        register(ADMIN_B, "tok-move");

        assertThat(cursorOf("tok-move")).isAfter(aDayAgo());
        assertThat(repository.findActiveByAdminId(ADMIN_B)).hasSize(1);
        assertThat(repository.findActiveByAdminId(ADMIN_A)).isEmpty();
    }

    @Test
    @DisplayName("le même admin qui ré-enregistre un appareil actif garde son curseur")
    void register_sameAdminActiveDevice_keepsCursor() throws Exception {
        register(ADMIN_A, "tok-same");
        ageCursor("tok-same");
        Instant aged = cursorOf("tok-same");
        assertThat(aged).isBefore(aDayAgo());

        register(ADMIN_A, "tok-same");

        assertThat(cursorOf("tok-same")).isEqualTo(aged);
    }

    // ── Écritures ciblées du répartiteur (F9) ────────────────────────────────

    @Test
    @DisplayName("advanceLastPushedAt n'avance que vers l'avant, pour le bon admin, sur un appareil actif")
    void advanceLastPushedAt_guards() throws Exception {
        register(ADMIN_A, "tok-adv");
        ageCursor("tok-adv");
        Instant aged = cursorOf("tok-adv");
        UUID id = repository.findAnyByFcmToken("tok-adv").orElseThrow().getId();
        Instant later = OLD.plusSeconds(3600);

        assertThat(repository.advanceLastPushedAt(id, ADMIN_A, later)).isEqualTo(1);
        Instant advanced = cursorOf("tok-adv");
        assertThat(advanced).isEqualTo(aged.plusSeconds(3600));
        // jamais en arrière
        assertThat(repository.advanceLastPushedAt(id, ADMIN_A, OLD)).isZero();
        // jeton repris par un autre admin entre la lecture et l'écriture
        assertThat(repository.advanceLastPushedAt(id, ADMIN_B, later.plusSeconds(60))).isZero();
        assertThat(cursorOf("tok-adv")).isEqualTo(advanced);
        // appareil désabonné entre-temps (le save du désabonnement réécrit le curseur relu : on relit)
        mockMvc.perform(delete("/admin/mobile/devices").param("fcmToken", "tok-adv")
                        .with(authentication(admin(ADMIN_A))))
                .andExpect(status().isNoContent());
        Instant unregistered = cursorOf("tok-adv");
        assertThat(repository.advanceLastPushedAt(id, ADMIN_A, later.plusSeconds(120))).isZero();
        assertThat(cursorOf("tok-adv")).isEqualTo(unregistered);
    }

    @Test
    @DisplayName("softDeleteActive supprime une fois, sans retoucher un appareil déjà supprimé")
    void softDeleteActive_onlyOnce() throws Exception {
        register(ADMIN_A, "tok-del");
        UUID id = repository.findAnyByFcmToken("tok-del").orElseThrow().getId();
        java.time.LocalDateTime first = java.time.LocalDateTime.parse("2026-11-26T10:00:00");

        assertThat(repository.softDeleteActive(id, first)).isEqualTo(1);
        assertThat(repository.softDeleteActive(id, first.plusHours(1))).isZero();

        assertThat(repository.findActive()).isEmpty();
        assertThat(repository.findAnyByFcmToken("tok-del").orElseThrow().getDeletedAt()).isEqualTo(first);
    }
}
