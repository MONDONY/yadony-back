package com.yadony.api.notifications;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.PageResponse;
import com.yadony.api.notifications.dto.AnnouncementsSummaryDTO;
import com.yadony.api.notifications.dto.FeedItemDTO;
import com.yadony.api.notifications.dto.NotificationDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NotificationFeedControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean NotificationFeedService feedService;
    @MockBean UserRepository userRepository;

    private static final String FIREBASE_UID = "uid-feed";

    @BeforeEach
    void setUp() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid(FIREBASE_UID);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
    }

    private UsernamePasswordAuthenticationToken asUser() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void feed_returnsAggregatedRowsWithCountAndIds() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        var row = new FeedItemDTO(a, "BID_CREATED", "colis", "3 demandes d'envoi", "Karim T., 12 kg, Paris vers Dakar.",
                "yadony://announcements/x/bids", "bid:announcement:x", Map.of("type", "BID_CREATED"), false,
                LocalDateTime.of(2026, 9, 3, 10, 0), 3, List.of(a, b, c));
        when(feedService.feed(FIREBASE_UID, 0, 30))
                .thenReturn(new PageResponse<>(List.of(row), 0, 30, 1, 1, true));

        mockMvc.perform(get("/notifications/feed").with(authentication(asUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].count").value(3))
                .andExpect(jsonPath("$.content[0].title").value("3 demandes d'envoi"))
                .andExpect(jsonPath("$.content[0].notificationIds.length()").value(3))
                .andExpect(jsonPath("$.content[0].fullBody").doesNotExist())
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void announcements_andSummary_areServed() throws Exception {
        UUID id = UUID.randomUUID();
        var dto = new NotificationDTO(id, "ADMIN_BROADCAST", "annonce", "Maintenance", "Corps court.", null,
                "notif:" + id, Map.of("type", "ADMIN_BROADCAST"), false, LocalDateTime.of(2026, 9, 3, 10, 0));
        when(feedService.announcements(FIREBASE_UID, 0, 30))
                .thenReturn(new PageResponse<>(List.of(dto), 0, 30, 1, 1, true));
        when(feedService.announcementsSummary(FIREBASE_UID))
                .thenReturn(new AnnouncementsSummaryDTO(2, id, "Maintenance", LocalDateTime.of(2026, 9, 3, 10, 0)));

        mockMvc.perform(get("/notifications/annonces").with(authentication(asUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].category").value("annonce"))
                .andExpect(jsonPath("$.content[0].fullBody").doesNotExist());
        mockMvc.perform(get("/notifications/annonces/summary").with(authentication(asUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2))
                .andExpect(jsonPath("$.latestTitle").value("Maintenance"));
    }

    @Test
    void markGroupRead_passesTheKeyAndReturnsCount() throws Exception {
        when(feedService.markGroupRead(FIREBASE_UID, "bid:announcement:x")).thenReturn(3);

        mockMvc.perform(patch("/notifications/groups/read")
                        .param("groupKey", "bid:announcement:x")
                        .with(authentication(asUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));

        verify(feedService).markGroupRead(FIREBASE_UID, "bid:announcement:x");
    }

    @Test
    void feed_withoutAuthentication_isRejected() throws Exception {
        mockMvc.perform(get("/notifications/feed"))
                .andExpect(status().isUnauthorized());
    }
}
