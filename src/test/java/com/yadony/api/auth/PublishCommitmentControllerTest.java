package com.yadony.api.auth;

import com.yadony.api.auth.dto.PublishCommitmentsResponse;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublishCommitmentControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthService authService;
    @MockBean PublishCommitmentService service;

    private static final UUID USER_ID = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(
                "uid-commitments", null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void GET_retourneLesDeuxRoles() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);
        when(service.get(USER_ID)).thenReturn(new PublishCommitmentsResponse(
                "2026-10",
                new PublishCommitmentsResponse.Status("2026-10", "2026-10-13T10:00:00Z", false),
                new PublishCommitmentsResponse.Status(null, null, true)));

        mvc.perform(get("/auth/me/publish-commitments").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value("2026-10"))
                .andExpect(jsonPath("$.traveler.required").value(false))
                .andExpect(jsonPath("$.traveler.acceptedVersion").value("2026-10"))
                .andExpect(jsonPath("$.sender.required").value(true));
    }

    @Test
    void PUT_valide_204() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);

        mvc.perform(put("/auth/me/publish-commitments")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TRAVELER\",\"version\":\"2026-10\"}"))
                .andExpect(status().isNoContent());

        verify(service).accept(USER_ID, PublishCommitmentRole.TRAVELER, "2026-10");
    }

    @Test
    void PUT_roleInconnuOuVersionVide_400() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);

        mvc.perform(put("/auth/me/publish-commitments")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SENDER\",\"version\":\"\"}"))
                .andExpect(status().is4xxClientError());

        verify(service, never()).accept(any(), any(), any());
    }

    @Test
    void PUT_versionPerimee_409ProblemDetail() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);
        doThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "publish-commitment-outdated",
                "Publish Commitment Outdated", "Les engagements ont été mis à jour"))
                .when(service).accept(USER_ID, PublishCommitmentRole.SENDER, "2020-01");

        mvc.perform(put("/auth/me/publish-commitments")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SENDER\",\"version\":\"2020-01\"}"))
                .andExpect(status().isConflict());
    }
}
