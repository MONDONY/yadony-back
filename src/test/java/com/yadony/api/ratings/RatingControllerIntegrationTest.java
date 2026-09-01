package com.yadony.api.ratings;

import com.yadony.api.ratings.dto.PendingRatingResponse;
import com.yadony.api.ratings.dto.RatingItemResponse;
import com.yadony.api.ratings.dto.TravelerRatingRequest;
import com.yadony.api.ratings.dto.UserRatingsSummaryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RatingControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean  private RatingService ratingService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final String TRAVELER_UID = "uid-traveler-test";
    private static final String SENDER_UID = "uid-sender-test";

    private static UsernamePasswordAuthenticationToken asRole(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void postTravelerRating_validRequest_returns201() throws Exception {
        var request = new TravelerRatingRequest(BID_ID, 5, "Très bien");
        when(ratingService.createTravelerRating(anyString(), any()))
                .thenReturn(new com.yadony.api.ratings.dto.RatingResponse(
                        UUID.randomUUID(), USER_ID, BID_ID, 5, "Très bien", LocalDateTime.now()));

        mockMvc.perform(post("/ratings/traveler-to-sender")
                        .with(authentication(asRole(TRAVELER_UID, "TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stars").value(5));
    }

    @Test
    void postTravelerRating_noAuth_returns401() throws Exception {
        var request = new TravelerRatingRequest(BID_ID, 4, null);
        mockMvc.perform(post("/ratings/traveler-to-sender")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getUserRatings_validUserId_returns200WithSummary() throws Exception {
        var summary = new UserRatingsSummaryResponse(
                new BigDecimal("4.50"), 2,
                Map.of(1, 0L, 2, 0L, 3, 0L, 4, 1L, 5, 1L),
                List.of(new RatingItemResponse(5, "Super", LocalDateTime.now(), false, null, null, null, null)),
                0, 1);
        // Appel anonyme : le contrôleur transmet un viewer nul, aucun masquage possible.
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(20), isNull())).thenReturn(summary);

        mockMvc.perform(get("/ratings/user/{userId}", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(4.50))
                .andExpect(jsonPath("$.ratingCount").value(2))
                .andExpect(jsonPath("$.ratings[0].stars").value(5));
    }

    @Test
    void getUserRatings_hiddenByBlock_returns404NotForbidden() throws Exception {
        // Le service masque un utilisateur bloqué par un 404 « not-found » : le contrôleur
        // le relaie tel quel. Un 403 rendrait le blocage détectable.
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(20), any()))
                .thenThrow(new com.yadony.api.common.YadonyBusinessException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "not-found",
                        "Not Found", "Ressource introuvable"));

        mockMvc.perform(get("/ratings/user/{userId}", USER_ID)
                        .with(authentication(asRole(SENDER_UID, "SENDER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void getPendingRating_hasPending_returns200() throws Exception {
        var pending = new PendingRatingResponse(
                BID_ID, "Moussa D.", USER_ID, LocalDateTime.now(), false);
        when(ratingService.getPendingRating(anyString())).thenReturn(Optional.of(pending));

        mockMvc.perform(get("/ratings/pending")
                        .with(authentication(asRole(SENDER_UID, "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bidId").value(BID_ID.toString()))
                .andExpect(jsonPath("$.isTravelerRating").value(false));
    }

    @Test
    void getPendingRating_noPending_returns204() throws Exception {
        when(ratingService.getPendingRating(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/ratings/pending")
                        .with(authentication(asRole(SENDER_UID, "SENDER"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void getPendingRating_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/ratings/pending"))
                .andExpect(status().isUnauthorized());
    }
}
