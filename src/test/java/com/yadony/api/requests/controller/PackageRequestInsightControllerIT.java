package com.yadony.api.requests.controller;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.requests.dto.PackageRequestInsightsResponse;
import com.yadony.api.requests.dto.PackageRequestInvitationResponse;
import com.yadony.api.requests.service.PackageRequestInsightService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PackageRequestInsightControllerIT {

    @Autowired private MockMvc mockMvc;
    @MockBean private PackageRequestInsightService service;
    @MockBean private UserRepository userRepository;

    private static final UUID SENDER_UUID = UUID.randomUUID();

    @BeforeEach
    void setupAuth() throws Exception {
        UserEntity sender = new UserEntity();
        var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(sender, SENDER_UUID);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
    }

    private static UsernamePasswordAuthenticationToken authAs(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void getInsights_returnsCountAndInvitedIds() throws Exception {
        UUID id = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        when(service.getInsights(SENDER_UUID, id)).thenReturn(new PackageRequestInsightsResponse(14, List.of(trip)));

        mockMvc.perform(get("/package-requests/" + id + "/insights").with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.viewCount").value(14))
            .andExpect(jsonPath("$.invitedAnnouncementIds[0]").value(trip.toString()));
    }

    @Test
    void getInsights_notFoundForNonOwner() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getInsights(SENDER_UUID, id))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        mockMvc.perform(get("/package-requests/" + id + "/insights").with(authentication(authAs("uid-sender", "SENDER"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void invite_newInvitation_returns201() throws Exception {
        UUID id = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        when(service.invite(eq(SENDER_UUID), eq(id), eq(trip))).thenReturn(new PackageRequestInsightService.InvitationResult(
            new PackageRequestInvitationResponse(trip, LocalDateTime.of(2026, 9, 17, 8, 0)), true));

        mockMvc.perform(post("/package-requests/" + id + "/invitations")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"announcementId\":\"" + trip + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.announcementId").value(trip.toString()));
    }

    @Test
    void invite_alreadyInvited_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        when(service.invite(eq(SENDER_UUID), eq(id), eq(trip))).thenReturn(new PackageRequestInsightService.InvitationResult(
            new PackageRequestInvitationResponse(trip, LocalDateTime.of(2026, 9, 17, 8, 0)), false));

        mockMvc.perform(post("/package-requests/" + id + "/invitations")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"announcementId\":\"" + trip + "\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void invite_missingAnnouncementId_returns422() throws Exception {
        mockMvc.perform(post("/package-requests/" + UUID.randomUUID() + "/invitations")
                .with(authentication(authAs("uid-sender", "SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invite_travelerRole_forbidden() throws Exception {
        mockMvc.perform(post("/package-requests/" + UUID.randomUUID() + "/invitations")
                .with(authentication(authAs("uid-sender", "TRAVELER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"announcementId\":\"" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isForbidden());
    }
}
