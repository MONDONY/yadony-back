package com.yadony.api.subscriptions;

import com.yadony.api.subscriptions.dto.SubscriberResponse;
import com.yadony.api.subscriptions.dto.SubscriptionItemResponse;
import com.yadony.api.subscriptions.dto.SubscriptionStatusResponse;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SubscriptionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean SubscriptionService subscriptionService;

    private static final String FIREBASE_UID = "uid-subscription-test";
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken asSender() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private UsernamePasswordAuthenticationToken asTraveler() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    // ── subscribe ─────────────────────────────────────────────────────────────

    @Test
    void subscribe_asSender_returns201() throws Exception {
        doNothing().when(subscriptionService).subscribe(FIREBASE_UID, TRAVELER_ID);

        mockMvc.perform(post("/travelers/{id}/subscribe", TRAVELER_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isCreated());

        verify(subscriptionService).subscribe(FIREBASE_UID, TRAVELER_ID);
    }

    @Test
    void subscribe_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/travelers/{id}/subscribe", TRAVELER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void subscribe_asTraveler_returns403() throws Exception {
        mockMvc.perform(post("/travelers/{id}/subscribe", TRAVELER_ID)
                        .with(authentication(asTraveler())))
                .andExpect(status().isForbidden());
    }

    // ── unsubscribe ───────────────────────────────────────────────────────────

    @Test
    void unsubscribe_asSender_returns204() throws Exception {
        doNothing().when(subscriptionService).unsubscribe(FIREBASE_UID, TRAVELER_ID);

        mockMvc.perform(delete("/travelers/{id}/subscribe", TRAVELER_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isNoContent());

        verify(subscriptionService).unsubscribe(FIREBASE_UID, TRAVELER_ID);
    }

    @Test
    void unsubscribe_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/travelers/{id}/subscribe", TRAVELER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsubscribe_asTraveler_returns403() throws Exception {
        mockMvc.perform(delete("/travelers/{id}/subscribe", TRAVELER_ID)
                        .with(authentication(asTraveler())))
                .andExpect(status().isForbidden());
    }

    // ── setPush ───────────────────────────────────────────────────────────────

    @Test
    void setPush_asSender_returns200WithStatus() throws Exception {
        SubscriptionStatusResponse statusResp = new SubscriptionStatusResponse(true, true);
        doNothing().when(subscriptionService).setPush(FIREBASE_UID, TRAVELER_ID, true);
        when(subscriptionService.getStatus(FIREBASE_UID, TRAVELER_ID)).thenReturn(statusResp);

        mockMvc.perform(put("/travelers/{id}/subscribe/push", TRAVELER_ID)
                        .with(authentication(asSender()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true))
                .andExpect(jsonPath("$.pushEnabled").value(true));

        verify(subscriptionService).setPush(FIREBASE_UID, TRAVELER_ID, true);
    }

    @Test
    void setPush_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/travelers/{id}/subscribe/push", TRAVELER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
    }

    // ── status ────────────────────────────────────────────────────────────────

    @Test
    void status_asSender_returns200() throws Exception {
        SubscriptionStatusResponse statusResp = new SubscriptionStatusResponse(true, false);
        when(subscriptionService.getStatus(FIREBASE_UID, TRAVELER_ID)).thenReturn(statusResp);

        mockMvc.perform(get("/travelers/{id}/subscription", TRAVELER_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true))
                .andExpect(jsonPath("$.pushEnabled").value(false));
    }

    @Test
    void status_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/travelers/{id}/subscription", TRAVELER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void status_asTraveler_returns403() throws Exception {
        mockMvc.perform(get("/travelers/{id}/subscription", TRAVELER_ID)
                        .with(authentication(asTraveler())))
                .andExpect(status().isForbidden());
    }

    // ── mySubscriptions ───────────────────────────────────────────────────────

    @Test
    void mySubscriptions_asSender_returns200EmptyList() throws Exception {
        when(subscriptionService.getMySubscriptions(FIREBASE_UID)).thenReturn(List.of());

        mockMvc.perform(get("/me/subscriptions")
                        .with(authentication(asSender())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void mySubscriptions_asSender_returnsItems() throws Exception {
        SubscriptionItemResponse item = new SubscriptionItemResponse(
                TRAVELER_ID, "Moussa T.", "https://cdn.yadony.app/signed/moussa.jpg", false, null, 2L, true, true, null);
        when(subscriptionService.getMySubscriptions(FIREBASE_UID)).thenReturn(List.of(item));

        mockMvc.perform(get("/me/subscriptions")
                        .with(authentication(asSender())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].travelerId").value(TRAVELER_ID.toString()))
                .andExpect(jsonPath("$[0].travelerName").value("Moussa T."))
                .andExpect(jsonPath("$[0].avatarUrl").value("https://cdn.yadony.app/signed/moussa.jpg"))
                .andExpect(jsonPath("$[0].pushEnabled").value(true))
                .andExpect(jsonPath("$[0].hasNew").value(true));
    }

    @Test
    void mySubscriptions_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/me/subscriptions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mySubscriptions_asTraveler_returns403() throws Exception {
        mockMvc.perform(get("/me/subscriptions")
                        .with(authentication(asTraveler())))
                .andExpect(status().isForbidden());
    }

    // ── markSeen ──────────────────────────────────────────────────────────────

    @Test
    void markSeen_asSender_returns204() throws Exception {
        doNothing().when(subscriptionService).markSeen(FIREBASE_UID, TRAVELER_ID);

        mockMvc.perform(post("/me/subscriptions/{id}/mark-seen", TRAVELER_ID)
                        .with(authentication(asSender())))
                .andExpect(status().isNoContent());

        verify(subscriptionService).markSeen(FIREBASE_UID, TRAVELER_ID);
    }

    @Test
    void markSeen_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/me/subscriptions/{id}/mark-seen", TRAVELER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void markSeen_asTraveler_returns403() throws Exception {
        mockMvc.perform(post("/me/subscriptions/{id}/mark-seen", TRAVELER_ID)
                        .with(authentication(asTraveler())))
                .andExpect(status().isForbidden());
    }

    // ── markAllSeen ───────────────────────────────────────────────────────────

    @Test
    void markAllSeen_asSender_returns204() throws Exception {
        doNothing().when(subscriptionService).markAllSeen(FIREBASE_UID);

        mockMvc.perform(post("/me/subscriptions/mark-seen")
                        .with(authentication(asSender())))
                .andExpect(status().isNoContent());

        verify(subscriptionService).markAllSeen(FIREBASE_UID);
    }

    @Test
    void markAllSeen_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/me/subscriptions/mark-seen"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void markAllSeen_asTraveler_returns403() throws Exception {
        mockMvc.perform(post("/me/subscriptions/mark-seen")
                        .with(authentication(asTraveler())))
                .andExpect(status().isForbidden());
    }

    // ── mySubscribers ─────────────────────────────────────────────────────────

    @Test
    void mySubscribers_asTraveler_returnsItems() throws Exception {
        UUID subscriberId = UUID.randomUUID();
        when(subscriptionService.getMySubscribers(FIREBASE_UID)).thenReturn(List.of(
                new SubscriberResponse(subscriberId, "Fatou N.", java.time.LocalDateTime.now())));

        mockMvc.perform(get("/me/subscribers")
                        .with(authentication(asTraveler())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].senderId").value(subscriberId.toString()))
                .andExpect(jsonPath("$[0].displayName").value("Fatou N."));
    }

    @Test
    void mySubscribers_asSender_returns403() throws Exception {
        mockMvc.perform(get("/me/subscribers")
                        .with(authentication(asSender())))
                .andExpect(status().isForbidden());
    }

    @Test
    void mySubscribers_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/me/subscribers"))
                .andExpect(status().isUnauthorized());
    }
}
