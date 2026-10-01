package com.yadony.api.auth;

import com.yadony.api.auth.dto.PrivacySettingsResponse;
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

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PrivacySettingsControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthService authService;

    private static final String FIREBASE_UID = "uid-privacy-test";

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void GET_privacySettings_retourne200() throws Exception {
        when(authService.getPrivacySettings(FIREBASE_UID))
                .thenReturn(new PrivacySettingsResponse(true, true));

        mvc.perform(get("/auth/me/privacy-settings")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactKycOnly").value(true))
                .andExpect(jsonPath("$.hidePhoneNumber").value(true));

        verify(authService).getPrivacySettings(FIREBASE_UID);
    }

    @Test
    void PUT_privacySettings_retourne204_etAppelleService() throws Exception {
        doNothing().when(authService).updatePrivacySettings(FIREBASE_UID, false, true, null, null);

        mvc.perform(put("/auth/me/privacy-settings")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contactKycOnly": false, "hidePhoneNumber": true}
                                """))
                .andExpect(status().isNoContent());

        verify(authService).updatePrivacySettings(FIREBASE_UID, false, true, null, null);
    }

    /**
     * Une version de l'app antérieure à {@code hidePhoneNumber} n'envoie que
     * {@code contactKycOnly} : la requête doit rester valide et le champ absent
     * arriver à null au service, qui laisse alors la préférence intacte.
     */
    @Test
    void PUT_privacySettings_sansHidePhoneNumber_passeNullAuService() throws Exception {
        doNothing().when(authService).updatePrivacySettings(FIREBASE_UID, false, null, null, null);

        mvc.perform(put("/auth/me/privacy-settings")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contactKycOnly": false}
                                """))
                .andExpect(status().isNoContent());

        verify(authService).updatePrivacySettings(FIREBASE_UID, false, null, null, null);
    }

    @Test
    void PUT_privacySettings_sansBody_retourne422() throws Exception {
        mvc.perform(put("/auth/me/privacy-settings")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void GET_privacySettings_sansAuth_retourne401() throws Exception {
        mvc.perform(get("/auth/me/privacy-settings")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void PUT_privacySettings_sansAuth_retourne401() throws Exception {
        mvc.perform(put("/auth/me/privacy-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contactKycOnly": false}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
