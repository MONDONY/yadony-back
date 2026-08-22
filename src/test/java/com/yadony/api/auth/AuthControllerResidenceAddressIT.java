package com.yadony.api.auth;

import com.yadony.api.auth.dto.ResidenceAddressRequest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthControllerResidenceAddressIT {

    private static final String FIREBASE_UID = "uid-residence-test";

    @Autowired MockMvc mvc;
    @MockBean AuthService authService;

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void PUT_residenceAddress_rueVide_retourne422() throws Exception {
        // GlobalExceptionHandler.handleValidation (GlobalExceptionHandler.java:43-58) mappe
        // MethodArgumentNotValidException (@Valid) inconditionnellement sur 422 UNPROCESSABLE_ENTITY,
        // jamais 400 : c'est le contrat effectif de l'API, on l'asserte tel quel plutôt que de
        // tolérer les deux (le voisin AnalyticsConsentControllerTest le tolère par héritage
        // historique, pas parce que le code peut vraiment répondre 400 ici).
        mvc.perform(put("/auth/me/residence-address")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"street":"","postalCode":"75011","city":"Paris"}
                            """))
                .andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(authService);
    }

    @Test
    void PUT_residenceAddress_valide_retourne204() throws Exception {
        mvc.perform(put("/auth/me/residence-address")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"street":"12 rue des Lilas","postalCode":"75011","city":"Paris"}
                            """))
                .andExpect(status().isNoContent());

        verify(authService).updateResidenceAddress(eq(FIREBASE_UID), any(ResidenceAddressRequest.class));
    }

    @Test
    void PUT_residenceAddress_sansAuthentification_retourne401() throws Exception {
        // SecurityConfig déclare /auth/me/residence-address authenticated() AVANT le
        // permitAll de /auth/** : défense en profondeur, indépendante du 401 déjà renvoyé
        // par requireFirebaseUid() dans le controller si ce garde venait à disparaître.
        mvc.perform(put("/auth/me/residence-address")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"street":"12 rue des Lilas","postalCode":"75011","city":"Paris"}
                            """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(authService);
    }

    @Test
    void PUT_onboardingSeen_retourne204() throws Exception {
        mvc.perform(put("/auth/me/onboarding-seen")
                        .with(authentication(auth())))
                .andExpect(status().isNoContent());

        verify(authService).markOnboardingSeen(FIREBASE_UID);
    }
}
