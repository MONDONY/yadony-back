package com.yadony.api.activation;

import com.yadony.api.activation.dto.ActivationResponse;
import com.yadony.api.activation.dto.DeclareIntentRequest;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ActivationControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ActivationService service;
    private static final String UID = "uid-activation-test";

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(UID, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private ActivationResponse sample() {
        return new ActivationResponse("SENDER", "CI", true, false,
                new ActivationResponse.Opportunities("TRIPS", 3, List.of(), List.of()));
    }

    @Test
    void GET_activation_returnsStatus() throws Exception {
        when(service.getActivation(UID)).thenReturn(sample());
        mvc.perform(get("/users/me/activation").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("SENDER"))
                .andExpect(jsonPath("$.kycVerified").value(true))
                .andExpect(jsonPath("$.firstActionDone").value(false))
                .andExpect(jsonPath("$.opportunities.kind").value("TRIPS"))
                .andExpect(jsonPath("$.opportunities.total").value(3));
    }

    @Test
    void GET_activation_withoutAuth_is401() throws Exception {
        mvc.perform(get("/users/me/activation")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void PUT_intent_callsService() throws Exception {
        when(service.declareIntent(eq(UID), any())).thenReturn(sample());
        mvc.perform(put("/users/me/intent").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intent": "SENDER", "destinationCountry": "CI", "source": "SIGNUP"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinationCountry").value("CI"));
        verify(service).declareIntent(UID, new DeclareIntentRequest(UserIntent.SENDER, "CI", IntentSource.SIGNUP));
    }

    @Test
    void PUT_intent_withoutDestination_isAccepted() throws Exception {
        when(service.declareIntent(eq(UID), any())).thenReturn(sample());
        mvc.perform(put("/users/me/intent").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intent": "BOTH", "source": "PROMPT"}
                                """))
                .andExpect(status().isOk());
        verify(service).declareIntent(UID, new DeclareIntentRequest(UserIntent.BOTH, null, IntentSource.PROMPT));
    }

    @Test
    void PUT_intent_invalidCountryFormat_is422() throws Exception {
        mvc.perform(put("/users/me/intent").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intent": "SENDER", "destinationCountry": "cote", "source": "SIGNUP"}
                                """))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(service);
    }

    @Test
    void PUT_intent_missingIntent_is422() throws Exception {
        mvc.perform(put("/users/me/intent").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source": "SIGNUP"}
                                """))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(service);
    }

    @Test
    void PUT_intent_withoutAuth_is401() throws Exception {
        mvc.perform(put("/users/me/intent").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intent": "SENDER", "source": "SIGNUP"}
                                """))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void PUT_intent_unknownIntentValue_is400() throws Exception {
        // Enum illisible : HttpMessageNotReadableException, 400 du GlobalExceptionHandler (comportement global du projet).
        mvc.perform(put("/users/me/intent").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intent": "FOO", "source": "SIGNUP"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
