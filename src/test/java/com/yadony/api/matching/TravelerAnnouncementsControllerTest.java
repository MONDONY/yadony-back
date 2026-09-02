package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TravelerAnnouncementsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean AnnouncementService announcementService;

    @Test
    void travelerAnnouncements_isPublic_returns200() throws Exception {
        UUID travelerId = UUID.randomUUID();
        when(announcementService.getTravelerAnnouncements(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/travelers/{id}/announcements", travelerId))
            .andExpect(status().isOk());

        // Anonyme : aucun viewer transmis, donc aucun masquage possible.
        verify(announcementService).getTravelerAnnouncements(isNull(), eq(travelerId));
    }

    @Test
    void travelerAnnouncements_authenticated_passesViewerUid() throws Exception {
        UUID travelerId = UUID.randomUUID();
        when(announcementService.getTravelerAnnouncements(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/travelers/{id}/announcements", travelerId)
                .with(authentication(new UsernamePasswordAuthenticationToken(
                        "viewer-uid", null, List.of(new SimpleGrantedAuthority("ROLE_SENDER"))))))
            .andExpect(status().isOk());

        // Connecté : l'identité du viewer sert à masquer un voyageur bloqué.
        verify(announcementService).getTravelerAnnouncements(eq("viewer-uid"), eq(travelerId));
    }
}
