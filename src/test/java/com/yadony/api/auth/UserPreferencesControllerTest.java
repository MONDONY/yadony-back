package com.yadony.api.auth;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.AppLanguage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@code PATCH /users/me/preferences} — MockMvc, modèle {@code PrivacySettingsControllerTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UserPreferencesControllerTest {

    @Autowired MockMvc mvc;
    @MockBean UserLanguageService userLanguageService;

    private static final String FIREBASE_UID = "uid-preferences-test";

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private UsernamePasswordAuthenticationToken guestAuth() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
    }

    @Test
    void PATCH_preferences_langueValide_retourne200EtAppelleService() throws Exception {
        when(userLanguageService.update(FIREBASE_UID, AppLanguage.EN)).thenReturn(AppLanguage.EN);

        mvc.perform(patch("/users/me/preferences")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language": "en"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("en"));

        verify(userLanguageService).update(FIREBASE_UID, AppLanguage.EN);
    }

    @Test
    void PATCH_preferences_langueNonSupportee_retourne422EnFrancais() throws Exception {
        mvc.perform(patch("/users/me/preferences")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language": "de"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.language")
                        .value("Langue non prise en charge : fr ou en attendu"));

        verify(userLanguageService, never()).update(anyString(), any());
    }

    /**
     * Bout en bout : Bean Validation suit l'{@code Accept-Language} de la requête
     * (D3 du plan i18n), pas seulement {@code MessagesResolver} pris isolément.
     */
    @Test
    void PATCH_preferences_langueNonSupportee_avecAcceptLanguageEn_retourne422EnAnglais() throws Exception {
        mvc.perform(patch("/users/me/preferences")
                        .with(authentication(auth()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language": "de"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.language")
                        .value("Unsupported language: fr or en expected"));
    }

    @Test
    void PATCH_preferences_sansLangue_retourne422() throws Exception {
        mvc.perform(patch("/users/me/preferences")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.language")
                        .value("La langue est obligatoire"));
    }

    @Test
    void PATCH_preferences_sansAuth_retourne401() throws Exception {
        mvc.perform(patch("/users/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language": "en"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void PATCH_preferences_invite_retourne403() throws Exception {
        mvc.perform(patch("/users/me/preferences")
                        .with(authentication(guestAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language": "en"}
                                """))
                .andExpect(status().isForbidden());

        verify(userLanguageService, never()).update(anyString(), any());
    }

    @Test
    void PATCH_preferences_utilisateurInconnu_retourne404() throws Exception {
        when(userLanguageService.update(FIREBASE_UID, AppLanguage.EN))
                .thenThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found",
                        "User Not Found", "Utilisateur introuvable"));

        mvc.perform(patch("/users/me/preferences")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language": "en"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
    }
}
