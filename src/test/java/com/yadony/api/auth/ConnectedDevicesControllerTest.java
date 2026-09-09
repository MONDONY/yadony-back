package com.yadony.api.auth;

import com.yadony.api.auth.dto.UserDeviceDto;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConnectedDevicesControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ConnectedDevicesService devicesService;
    @MockBean AuthService authService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String FIREBASE_UID = "uid-devices-test";

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void DELETE_auth_me_fcm_token_retourne204_etOublieLAppareilCourant() throws Exception {
        mvc.perform(delete("/auth/me/fcm-token")
                        .param("deviceId", "dev-1")
                        .with(authentication(auth())))
                .andExpect(status().isNoContent());

        verify(authService).forgetFcmToken(FIREBASE_UID, "dev-1");
    }

    @Test
    void GET_devices_retourne200() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);
        when(devicesService.listDevices(eq(USER_ID), any())).thenReturn(List.of(
                new UserDeviceDto("dev-1", "iPhone 14 Pro", "ios",
                        OffsetDateTime.now(), true)
        ));

        mvc.perform(get("/users/me/devices")
                        .with(authentication(auth()))
                        .header("X-Device-Id", "dev-1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceId").value("dev-1"))
                .andExpect(jsonPath("$[0].isCurrent").value(true));
    }

    @Test
    void DELETE_device_retourne204() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);
        doNothing().when(devicesService).revokeDevice(eq(USER_ID), eq("dev-2"), eq("dev-1"));

        mvc.perform(delete("/users/me/devices/dev-2")
                        .with(authentication(auth()))
                        .header("X-Device-Id", "dev-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void DELETE_others_retourne204() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);
        doNothing().when(devicesService).revokeOthers(eq(USER_ID), eq("dev-1"));

        mvc.perform(delete("/users/me/devices/others")
                        .with(authentication(auth()))
                        .header("X-Device-Id", "dev-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void GET_devices_sans_auth_retourne401() throws Exception {
        mvc.perform(get("/users/me/devices")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void DELETE_device_sans_auth_retourne401() throws Exception {
        mvc.perform(delete("/users/me/devices/dev-2")
                        .header("X-Device-Id", "dev-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void DELETE_others_sans_auth_retourne401() throws Exception {
        mvc.perform(delete("/users/me/devices/others")
                        .header("X-Device-Id", "dev-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void POST_register_web_retourne204() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);
        doNothing().when(devicesService).upsertDevice(eq(USER_ID), eq("web-session-abc"),
                eq("Chrome / Windows"), eq("web"), isNull());

        mvc.perform(post("/users/me/devices")
                        .with(authentication(auth()))
                        .header("X-Device-Id", "web-session-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceName": "Chrome / Windows", "platform": "web"}
                                """))
                .andExpect(status().isNoContent());

        verify(devicesService).upsertDevice(USER_ID, "web-session-abc", "Chrome / Windows", "web", null);
    }

    @Test
    void POST_register_sans_deviceId_retourne400() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);

        mvc.perform(post("/users/me/devices")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceName": "Chrome / Windows", "platform": "web"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_register_platform_invalide_retourne422() throws Exception {
        when(authService.requireUserId()).thenReturn(USER_ID);

        mvc.perform(post("/users/me/devices")
                        .with(authentication(auth()))
                        .header("X-Device-Id", "web-session-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceName": "Appareil test", "platform": "desktop"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void POST_register_sans_auth_retourne401() throws Exception {
        mvc.perform(post("/users/me/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceName": "Chrome / Windows", "platform": "web"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
