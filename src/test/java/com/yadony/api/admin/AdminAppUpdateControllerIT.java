package com.yadony.api.admin;

import com.google.firebase.ErrorCode;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;
import com.google.firebase.remoteconfig.RemoteConfigErrorCode;
import com.google.firebase.remoteconfig.Template;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.common.AuditService;
import com.yadony.api.config.RemoteConfigGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
@DisplayName("AdminAppUpdateControllerIT — /admin/app-update")
class AdminAppUpdateControllerIT {

    private static final String URL = "/admin/app-update";
    private static final String TEMPLATE_JSON = """
            {"parameters": {
              "min_supported_build": {"defaultValue": {"value": "42"}},
              "recommended_build": {"defaultValue": {"value": "50"}}}}""";

    @Autowired MockMvc mockMvc;

    @MockitoBean RemoteConfigGateway gateway;
    @MockitoBean AuditService auditService;

    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-app-update");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("CONFIG_MANAGE")));
    }

    /** SUPPORT porte ROLE_ADMIN mais pas CONFIG_MANAGE. */
    private static UsernamePasswordAuthenticationToken supportAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT, false, "uid-support-app-update");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    @DisplayName("GET — SUPPORT (sans CONFIG_MANAGE) -> 403")
    void getSupportForbidden() throws Exception {
        mockMvc.perform(get(URL).with(authentication(supportAuth()))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT — SUPPORT -> 403, rien n'est lu ni publie")
    void putSupportForbidden() throws Exception {
        mockMvc.perform(put(URL).with(authentication(supportAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recommendedBuild\": 60}"))
                .andExpect(status().isForbidden());
        verify(gateway, never()).publish(any());
    }

    @Test
    @DisplayName("GET — sans authentification -> refuse")
    void getAnonymous() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("GET — ADMIN -> 200 avec les deux builds")
    void getOk() throws Exception {
        when(gateway.fetch()).thenReturn(Template.fromJSON(TEMPLATE_JSON));
        mockMvc.perform(get(URL).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedBuild").value(50))
                .andExpect(jsonPath("$.minSupportedBuild").value(42));
    }

    @Test
    @DisplayName("PUT — ADMIN -> 200 avec la nouvelle valeur, audit ecrit")
    void putOk() throws Exception {
        Template template = Template.fromJSON(TEMPLATE_JSON);
        when(gateway.fetch()).thenReturn(template);
        when(gateway.publish(template)).thenReturn(template);

        mockMvc.perform(put(URL).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recommendedBuild\": 60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedBuild").value(60))
                .andExpect(jsonPath("$.minSupportedBuild").value(42));
        verify(auditService).log(any(), any(), org.mockito.ArgumentMatchers.eq("APP_RECOMMENDED_BUILD_CHANGED"),
                any(), any());
    }

    @Test
    @DisplayName("PUT — hors bornes ou absent -> 422 RFC 7807 en francais")
    void putInvalid() throws Exception {
        for (String body : new String[]{"{\"recommendedBuild\": -1}", "{\"recommendedBuild\": 100001}", "{}"}) {
            mockMvc.perform(put(URL).with(authentication(adminAuth()))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value(
                            "Le build recommande doit etre un entier compris entre 0 et 100000"));
        }
        verify(gateway, never()).publish(any());
    }

    @Test
    @DisplayName("PUT — conflit d'ETag -> 409")
    void putConflict() throws Exception {
        Template template = Template.fromJSON(TEMPLATE_JSON);
        when(gateway.fetch()).thenReturn(template);
        when(gateway.publish(any())).thenThrow(new FirebaseRemoteConfigException(
                ErrorCode.FAILED_PRECONDITION, "etag", null, null, RemoteConfigErrorCode.VERSION_MISMATCH));

        mockMvc.perform(put(URL).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recommendedBuild\": 60}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La configuration a changé entre-temps, réessaie."));
    }

    @Test
    @DisplayName("GET et PUT — Firebase injoignable ou permission refusee -> 503")
    void firebaseUnavailable() throws Exception {
        when(gateway.fetch()).thenThrow(new FirebaseRemoteConfigException(
                ErrorCode.PERMISSION_DENIED, "denied", null, null, null));

        mockMvc.perform(get(URL).with(authentication(adminAuth())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(
                        "Firebase Remote Config est injoignable ou le compte de service n'a pas le droit de publier."));
        mockMvc.perform(put(URL).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recommendedBuild\": 60}"))
                .andExpect(status().isServiceUnavailable());
    }
}
