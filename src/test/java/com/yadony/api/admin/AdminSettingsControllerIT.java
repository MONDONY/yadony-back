package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingKey;
import com.yadony.api.config.PlatformSettingView;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot D — matrice de permission et contrat HTTP des parametres plateforme.
 *
 * <p>SUPPORT ne recoit PAS {@code CONFIG_MANAGE} : lecture comme ecriture lui sont fermees.
 * La lecture est protegee au meme titre que l'ecriture — le taux de commission et l'etat
 * des SMS renseignent sur l'economie de la plateforme.
 *
 * <p>Le contrat est <b>cle/valeur</b>, celui que le back-office consomme deja
 * ({@code settingsService.ts}, livre en tache 14) : {@code GET} renvoie une LISTE, et
 * {@code PUT /admin/settings/{key}} ecrit UNE cle. Chaque reglage porte sa propre date et
 * son propre auteur, sans quoi on ne saurait plus qui a change quoi.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminSettingsControllerIT — /admin/settings")
class AdminSettingsControllerIT {

    @Autowired MockMvc mockMvc;

    @MockitoBean PlatformSettingsService settingsService;
    @MockitoBean AdminUserRepository adminUserRepository;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID EDITOR_ID = UUID.randomUUID();
    private static final LocalDateTime EDITED_AT = LocalDateTime.of(2026, 8, 19, 10, 0);

    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-settings");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("CONFIG_MANAGE")));
    }

    /** SUPPORT porte ROLE_ADMIN comme tout compte admin — seule l'authority discrimine. */
    private static UsernamePasswordAuthenticationToken supportAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT, false, "uid-support-settings");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET — SUPPORT (sans CONFIG_MANAGE) → 403")
    void get_withSupportRole_returns403() throws Exception {
        mockMvc.perform(get("/admin/settings").with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET — ADMIN → 200, les quatre reglages avec leur type et leur auteur")
    void get_withAdmin_returnsEveryKeyWithItsOwnEditor() throws Exception {
        when(settingsService.listByKey()).thenReturn(List.of(
                new PlatformSettingView(PlatformSettingKey.COMMISSION_RATE, "0.05", EDITED_AT, EDITOR_ID),
                new PlatformSettingView(PlatformSettingKey.URGENCY_THRESHOLD_DAYS, "3", null, null),
                new PlatformSettingView(PlatformSettingKey.REIMBURSEMENT_CAP_EUR, "50", null, null),
                new PlatformSettingView(PlatformSettingKey.SMS_ENABLED, "false", null, null)));
        // Les auteurs sont resolus en UN appel groupe, pas un findById par ligne : avec quatre
        // reglages, la version naive ferait quatre requetes pour afficher un ecran.
        AdminUserEntity editor = new AdminUserEntity("uid-editor", "editeur@yadony.test", AdminRole.ADMIN);
        ReflectionTestUtils.setField(editor, "id", EDITOR_ID);
        when(adminUserRepository.findAllById(Set.of(EDITOR_ID))).thenReturn(List.of(editor));

        mockMvc.perform(get("/admin/settings").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                // La cle exposee est celle de la TABLE (commission_rate), pas le nom de la
                // constante Java : c'est elle que le PUT reprend dans son chemin.
                .andExpect(jsonPath("$[0].key").value("commission_rate"))
                .andExpect(jsonPath("$[0].value").value("0.05"))
                .andExpect(jsonPath("$[0].type").value("DECIMAL"))
                .andExpect(jsonPath("$[0].updatedByEmail").value("editeur@yadony.test"))
                .andExpect(jsonPath("$[1].key").value("urgency_threshold_days"))
                .andExpect(jsonPath("$[1].type").value("INTEGER"))
                .andExpect(jsonPath("$[3].key").value("sms_enabled"))
                .andExpect(jsonPath("$[3].type").value("BOOLEAN"));
    }

    @Test
    @DisplayName("GET — reglage jamais modifie : ni date ni auteur, pour ne pas afficher le seed comme une modification")
    void get_neverEdited_omitsEditorFields() throws Exception {
        when(settingsService.listByKey()).thenReturn(List.of(
                new PlatformSettingView(PlatformSettingKey.SMS_ENABLED, "false", null, null)));

        mockMvc.perform(get("/admin/settings").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].updatedAt").doesNotExist())
                .andExpect(jsonPath("$[0].updatedByEmail").doesNotExist());
    }

    // ── PUT ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT — SUPPORT (sans CONFIG_MANAGE) → 403 et rien n'est ecrit")
    void put_withSupportRole_returns403() throws Exception {
        mockMvc.perform(put("/admin/settings/commission_rate")
                        .with(authentication(supportAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"0.07\"}"))
                .andExpect(status().isForbidden());

        verify(settingsService, never()).updateOne(any(), anyString(), any());
    }

    @Test
    @DisplayName("PUT — ADMIN → 200, la cle du chemin et la valeur du corps sont transmises telles quelles")
    void put_withAdmin_writesTheKeyFromThePath() throws Exception {
        when(settingsService.updateOne(eq(PlatformSettingKey.COMMISSION_RATE), eq("0.07"), eq(ADMIN_ID)))
                .thenReturn(new PlatformSettingView(
                        PlatformSettingKey.COMMISSION_RATE, "0.07", EDITED_AT, ADMIN_ID));
        when(adminUserRepository.findById(ADMIN_ID)).thenReturn(Optional.empty());

        mockMvc.perform(put("/admin/settings/commission_rate")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"0.07\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("commission_rate"))
                .andExpect(jsonPath("$.value").value("0.07"));

        verify(settingsService).updateOne(PlatformSettingKey.COMMISSION_RATE, "0.07", ADMIN_ID);
    }

    @Test
    @DisplayName("PUT — valeur vide → 422 et rien n'est ecrit")
    void put_withBlankValue_returns422() throws Exception {
        mockMvc.perform(put("/admin/settings/commission_rate")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());

        verify(settingsService, never()).updateOne(any(), anyString(), any());
    }

    @Test
    @DisplayName("PUT — cle inconnue → 422, on n'invente pas un reglage")
    void put_withUnknownKey_returns422() throws Exception {
        mockMvc.perform(put("/admin/settings/commission_rate_percent")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"12\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").exists());

        verify(settingsService, never()).updateOne(any(), anyString(), any());
    }

    @Test
    @DisplayName("PUT — borne depassee : le 422 du service remonte en RFC 7807")
    void put_outOfRange_propagatesProblemDetail() throws Exception {
        doThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "platform-setting-invalid", "Unprocessable Entity",
                "Le taux de commission doit etre compris entre 0 et 30 %"))
                .when(settingsService).updateOne(any(), anyString(), any());

        mockMvc.perform(put("/admin/settings/commission_rate")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"0.90\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("Le taux de commission doit etre compris entre 0 et 30 %"));
    }
}
