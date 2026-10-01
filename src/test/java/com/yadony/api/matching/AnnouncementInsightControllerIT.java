package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementInsightsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnnouncementInsightControllerIT {

    @Autowired private MockMvc mockMvc;
    @MockBean private AnnouncementViewService service;
    @MockBean private UserRepository userRepository;

    private static final UUID USER_UUID = UUID.randomUUID();

    @BeforeEach
    void setupAuth() throws Exception {
        UserEntity user = new UserEntity();
        var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(user, USER_UUID);
        when(userRepository.findByFirebaseUid("uid-user")).thenReturn(Optional.of(user));
    }

    private static UsernamePasswordAuthenticationToken authAs(String role) {
        return new UsernamePasswordAuthenticationToken("uid-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void recordView_records_forTheCaller_andAnswers204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/announcements/" + id + "/views").with(authentication(authAs("SENDER"))))
            .andExpect(status().isNoContent());

        verify(service).recordView(USER_UUID, id);
    }

    @Test
    void recordView_unknownTrip_isNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found",
                "Announcement Not Found", "Annonce introuvable"))
            .when(service).recordView(USER_UUID, id);

        mockMvc.perform(post("/announcements/" + id + "/views").with(authentication(authAs("SENDER"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void recordView_withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(post("/announcements/" + UUID.randomUUID() + "/views"))
            .andExpect(status().isUnauthorized());

        verify(service, never()).recordView(any(), any());
    }

    @Test
    void recordView_guest_isForbidden() throws Exception {
        mockMvc.perform(post("/announcements/" + UUID.randomUUID() + "/views").with(authentication(authAs("GUEST"))))
            .andExpect(status().isForbidden());

        verify(service, never()).recordView(any(), any());
    }

    @Test
    void insights_owner_returnsUniqueViewersAndPosterViews() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getInsights(USER_UUID, id)).thenReturn(new AnnouncementInsightsResponse(12, 7));

        mockMvc.perform(get("/announcements/" + id + "/insights").with(authentication(authAs("TRAVELER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.uniqueViewerCount").value(12))
            .andExpect(jsonPath("$.shareViewCount").value(7));
    }

    @Test
    void insights_someoneElse_isNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getInsights(USER_UUID, id)).thenThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        mockMvc.perform(get("/announcements/" + id + "/insights").with(authentication(authAs("TRAVELER"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void insights_senderOnly_isForbidden() throws Exception {
        mockMvc.perform(get("/announcements/" + UUID.randomUUID() + "/insights").with(authentication(authAs("SENDER"))))
            .andExpect(status().isForbidden());

        verify(service, never()).getInsights(any(), any());
    }
}
