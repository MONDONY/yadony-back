package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementDetailResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Câblage de {@code POST /announcements/{id}/hide} et {@code /show} : 200 + champ {@code hidden}, 403, 409. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnnouncementHideControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AnnouncementService announcementService;

    private static UsernamePasswordAuthenticationToken traveler(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static AnnouncementDetailResponse detail(UUID id, boolean hidden) {
        return new AnnouncementDetailResponse(
                id, UUID.randomUUID(), "Paris", "Dakar", null, null, null, null,
                null, null, null, null, null, null, null, null,
                "ACTIVE", 0L, 0L, null, null,
                List.of(), List.of(), List.of(), null, false,
                null, null, null, List.of(), null,
                false, false, null, "EUR", null, false,
                java.util.Set.of(com.yadony.api.payments.cash.PaymentMethod.CASH),
                null, null, null, 2, "FR", "SN").withHidden(hidden);
    }

    @Test
    void hide_returns200_withHiddenTrue() throws Exception {
        UUID id = UUID.randomUUID();
        when(announcementService.hideAnnouncement(eq(id), anyString())).thenReturn(detail(id, true));

        mockMvc.perform(post("/announcements/" + id + "/hide").with(authentication(traveler("uid-hide"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void show_returns200_withHiddenFalse() throws Exception {
        UUID id = UUID.randomUUID();
        when(announcementService.showAnnouncement(eq(id), anyString())).thenReturn(detail(id, false));

        mockMvc.perform(post("/announcements/" + id + "/show").with(authentication(traveler("uid-show"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(false));
    }

    @Test
    void hide_notHideable_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(announcementService.hideAnnouncement(eq(id), anyString()))
                .thenThrow(YadonyBusinessException.localized(HttpStatus.CONFLICT,
                        "announcement/not-hideable", "problem.announcement-not-hideable"));

        mockMvc.perform(post("/announcements/" + id + "/hide").with(authentication(traveler("uid-hide-409"))))
                .andExpect(status().isConflict());
    }

    @Test
    void show_notOwner_returns403() throws Exception {
        UUID id = UUID.randomUUID();
        when(announcementService.showAnnouncement(eq(id), anyString()))
                .thenThrow(YadonyBusinessException.localized(HttpStatus.FORBIDDEN,
                        "forbidden", "problem.forbidden.depublier-cette-annonce"));

        mockMvc.perform(post("/announcements/" + id + "/show").with(authentication(traveler("uid-show-403"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void hide_unauthenticated_isRejected() throws Exception {
        mockMvc.perform(post("/announcements/" + UUID.randomUUID() + "/hide"))
                .andExpect(status().is4xxClientError());
    }
}
