package com.yadony.api.matching;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.dto.MatchingRequestDto;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MatchingRequestsEndpointTest {

    @Autowired MockMvc mockMvc;

    @MockBean TravelerStatsService statsService;
    @MockBean UserRepository userRepository;
    @MockBean ProAnalyticsService analyticsService;
    @MockBean AnnouncementRepository announcementRepository;
    @MockBean MatchingService matchingService;
    @MockBean PackageRequestRepository packageRequestRepository;
    @MockBean NotificationDispatcher notificationDispatcher;
    @MockBean BidService bidService;
    @MockBean AnnouncementService announcementService;

    private static final String FIREBASE_UID = "uid-matching-test";
    private final UUID travelerId = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken asTraveler() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @BeforeEach
    void setup() {
        UserEntity nonProTraveler = new UserEntity();
        nonProTraveler.setRoles(Set.of(Role.TRAVELER));
        nonProTraveler.setProAccount(false);
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(nonProTraveler, travelerId);
        } catch (Exception e) { throw new RuntimeException(e); }
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(nonProTraveler));
    }

    @Test
    void nonProTraveler_getsResults_noMoreForbidden() throws Exception {
        MatchingRequestDto dto = new MatchingRequestDto(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Paris → Bamako",
                "2026-07-10", 23.0, UUID.randomUUID().toString(), "Awa K", "AK", 4.5, 3,
                3.0, "Documents", 8.0, null, "excerpt", 80, "2026-06-20T10:00:00", "EUR");
        when(matchingService.findMatchingRequests(travelerId)).thenReturn(List.of(dto));

        mockMvc.perform(get("/travelers/me/matching-requests").with(authentication(asTraveler())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contentType").value("Documents"));
    }
}
