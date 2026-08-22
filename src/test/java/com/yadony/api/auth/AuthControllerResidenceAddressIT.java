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
    void PUT_residenceAddress_rueVide_retourne400ou422() throws Exception {
        // GlobalExceptionHandler mappe MethodArgumentNotValidException (@Valid) sur 422,
        // pas 400 (cf. AnalyticsConsentControllerTest.PUT_analyticsConsent_grantedManquant_retourne400ou422,
        // le test voisin le plus proche, qui tolère déjà les deux).
        mvc.perform(put("/auth/me/residence-address")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"street":"","postalCode":"75011","city":"Paris"}
                            """))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 400 && status != 422) {
                        throw new AssertionError("Attendu 400 ou 422, obtenu " + status);
                    }
                });

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
    void PUT_onboardingSeen_retourne204() throws Exception {
        mvc.perform(put("/auth/me/onboarding-seen")
                        .with(authentication(auth())))
                .andExpect(status().isNoContent());

        verify(authService).markOnboardingSeen(FIREBASE_UID);
    }
}
