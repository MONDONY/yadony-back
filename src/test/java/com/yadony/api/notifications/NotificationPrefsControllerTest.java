package com.yadony.api.notifications;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
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
class NotificationPrefsControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean NotificationPrefsService notificationPrefsService;
    @MockBean UserRepository userRepository;

    private static final String FIREBASE_UID = "uid-test";

    @BeforeEach
    void setUp() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid(FIREBASE_UID);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
    }

    private UsernamePasswordAuthenticationToken asUser() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void getPreferences_authenticated_returns200WithDefaults() throws Exception {
        when(notificationPrefsService.getPrefs(FIREBASE_UID))
                .thenReturn(NotificationPrefsDto.defaults());

        mockMvc.perform(get("/notifications/preferences")
                        .with(authentication(asUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushActivityBids").value(true))
                .andExpect(jsonPath("$.pushActivityNegotiations").value(true))
                .andExpect(jsonPath("$.pushMessages").value(true))
                .andExpect(jsonPath("$.pushTripReminder").value(true))
                .andExpect(jsonPath("$.pushPromo").value(false))
                .andExpect(jsonPath("$.pushMissedCalls").value(true))
                .andExpect(jsonPath("$.pushTravelerAutomations").value(true))
                .andExpect(jsonPath("$.pushRemindersTips").value(true));
    }

    /** Ancienne application : six champs seulement, les trois nouveaux arrivent à null. */
    @Test
    void updatePreferences_legacyPayload_deserializesNewFieldsAsNull() throws Exception {
        String legacy = "{\"pushActivityBids\":true,\"pushActivityNegotiations\":true,"
                + "\"pushMessages\":true,\"pushTripReminder\":false,\"pushPromo\":false,"
                + "\"pushCorridorAlerts\":true}";
        mockMvc.perform(put("/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacy)
                        .with(authentication(asUser())))
                .andExpect(status().isNoContent());

        verify(notificationPrefsService).upsert(eq(FIREBASE_UID), argThat(dto ->
                dto.pushMissedCalls() == null
                        && dto.pushTravelerAutomations() == null
                        && dto.pushRemindersTips() == null));
    }

    @Test
    void updatePreferences_newFields_reachTheService() throws Exception {
        String body = "{\"pushActivityBids\":true,\"pushActivityNegotiations\":true,"
                + "\"pushMessages\":true,\"pushTripReminder\":true,\"pushPromo\":false,"
                + "\"pushCorridorAlerts\":true,\"pushMissedCalls\":false,"
                + "\"pushTravelerAutomations\":false,\"pushRemindersTips\":false}";
        mockMvc.perform(put("/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(asUser())))
                .andExpect(status().isNoContent());

        verify(notificationPrefsService).upsert(eq(FIREBASE_UID), argThat(dto ->
                Boolean.FALSE.equals(dto.pushMissedCalls())
                        && Boolean.FALSE.equals(dto.pushTravelerAutomations())
                        && Boolean.FALSE.equals(dto.pushRemindersTips())));
    }

    @Test
    void getPreferences_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/notifications/preferences"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updatePreferences_authenticated_returns204AndCallsService() throws Exception {
        NotificationPrefsDto dto = new NotificationPrefsDto(false, true, true, false, false, true);
        doNothing().when(notificationPrefsService).upsert(eq(FIREBASE_UID), any());

        mockMvc.perform(put("/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .with(authentication(asUser())))
                .andExpect(status().isNoContent());

        verify(notificationPrefsService).upsert(eq(FIREBASE_UID), any(NotificationPrefsDto.class));
    }

    @Test
    void updatePreferences_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(NotificationPrefsDto.defaults())))
                .andExpect(status().isUnauthorized());
    }
}
