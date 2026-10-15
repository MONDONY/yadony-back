package com.yadony.api.ratings;

import com.yadony.api.ratings.dto.RatingItemResponse;
import com.yadony.api.ratings.dto.UserRatingsSummaryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MyReceivedRatingsControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean  RatingService ratingService;

    private static final String SENDER_UID = "uid-sender-me";
    private static final String TRAVELER_UID = "uid-traveler-me";

    private static UsernamePasswordAuthenticationToken asRole(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private UserRatingsSummaryResponse stubSummary() {
        return new UserRatingsSummaryResponse(
                new BigDecimal("4.75"),
                4,
                Map.of(1, 0L, 2, 0L, 3, 0L, 4, 1L, 5, 3L),
                List.of(new RatingItemResponse(UUID.randomUUID(), 5, "Parfait", LocalDateTime.now(), false, null, null, null, null)),
                0,
                1);
    }

    @Test
    void getMine_asAnonymous_returns401() throws Exception {
        mockMvc.perform(get("/ratings/me/received"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMine_asSender_returns200() throws Exception {
        when(ratingService.getMyReceivedRatings(anyString(), anyInt(), anyInt()))
                .thenReturn(stubSummary());

        mockMvc.perform(get("/ratings/me/received")
                        .with(authentication(asRole(SENDER_UID, "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(4.75))
                .andExpect(jsonPath("$.ratingCount").value(4))
                .andExpect(jsonPath("$.ratings[0].stars").value(5));
    }

    @Test
    void getMine_asTraveler_returns200() throws Exception {
        when(ratingService.getMyReceivedRatings(anyString(), anyInt(), anyInt()))
                .thenReturn(stubSummary());

        mockMvc.perform(get("/ratings/me/received")
                        .with(authentication(asRole(TRAVELER_UID, "TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(4.75));
    }
}
