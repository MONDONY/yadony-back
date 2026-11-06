package com.yadony.api.admin.account;

import com.yadony.api.admin.account.dto.CreateAdminRequest;
import com.yadony.api.admin.account.dto.CredentialsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code POST /admin/bootstrap} — Task 8.
 *
 * Organised in three nested classes, each with its own property override:
 * <ol>
 *   <li>{@code WhenSecretNotConfigured} — {@code admin.bootstrap.secret} empty (default)</li>
 *   <li>{@code WhenSecretConfigured} — {@code admin.bootstrap.secret=test-bootstrap-secret-123}</li>
 * </ol>
 */
@DisplayName("AdminBootstrapControllerIT — POST /admin/bootstrap")
class AdminBootstrapControllerIT {

    // =========================================================================
    // Nested class 1: secret NOT configured → endpoint returns 404 always
    // =========================================================================

    @Nested
    @DisplayName("When ADMIN_BOOTSTRAP_SECRET is not configured")
    @SpringBootTest
    @ActiveProfiles("test")
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "yadony.admin.bootstrap.secret=")
    class WhenSecretNotConfigured {

        @Autowired
        MockMvc mockMvc;

        @MockitoBean
        AdminAccountService adminAccountService;

        @MockitoBean
        AdminUserRepository adminUserRepository;

        @Test
        @DisplayName("POST without header → 404")
        void noSecret_noHeader_returns404() throws Exception {
            mockMvc.perform(post("/admin/bootstrap"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("POST with any header → still 404")
        void noSecret_withHeader_returns404() throws Exception {
            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "any-value"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Repository and service are never called when bootstrap is disabled")
        void noSecret_servicesNeverCalled() throws Exception {
            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "whatever"))
                    .andExpect(status().isNotFound());

            verify(adminAccountService, never()).bootstrapSuperAdmin(any(), any());
            verify(adminAccountService, never()).createAdmin(any(), any());
            verify(adminAccountService, never()).resetPassword(any(), any());
            verify(adminUserRepository, never()).countByRole(any());
        }
    }

    // =========================================================================
    // Nested class 2: secret IS configured
    // =========================================================================

    @Nested
    @DisplayName("When ADMIN_BOOTSTRAP_SECRET is configured")
    @SpringBootTest
    @ActiveProfiles("test")
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "yadony.admin.root-email=root@yadony.test",
            "yadony.admin.bootstrap.secret=test-bootstrap-secret-123",
            "yadony.admin.bootstrap.email=root@yadony.test",
            "yadony.admin.bootstrap.password=test-only-value"
    })
    class WhenSecretConfigured {

        static final String VALID_SECRET = "test-bootstrap-secret-123";

        @Autowired
        MockMvc mockMvc;

        @Autowired
        ObjectMapper objectMapper;

        @MockitoBean
        AdminAccountService adminAccountService;

        @MockitoBean
        AdminUserRepository adminUserRepository;

        // ── 403 cases ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("POST without X-Bootstrap-Secret header → 403 RFC 7807")
        void missingHeader_returns403() throws Exception {
            mockMvc.perform(post("/admin/bootstrap"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.detail").value("Invalid bootstrap secret"));
        }

        @Test
        @DisplayName("POST with wrong X-Bootstrap-Secret → 403 RFC 7807")
        void wrongHeader_returns403() throws Exception {
            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "wrong-secret"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.detail").value("Invalid bootstrap secret"));
        }

        @Test
        @DisplayName("403 response has RFC 7807 type URI")
        void wrongHeader_403_hasRfc7807TypeUri() throws Exception {
            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "bad"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://yadony.app/errors/forbidden"));
        }

        @Test
        @DisplayName("Services not called when secret is wrong")
        void wrongSecret_servicesNeverCalled() throws Exception {
            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "wrong"))
                    .andExpect(status().isForbidden());

            verify(adminAccountService, never()).bootstrapSuperAdmin(any(), any());
            verify(adminAccountService, never()).createAdmin(any(), any());
            verify(adminAccountService, never()).resetPassword(any(), any());
            verify(adminUserRepository, never()).countByRole(any());
        }

        // ── 201 create mode: no super-admin exists ─────────────────────────────

        @Test
        @DisplayName("Secret OK, no super-admin → 201 with the canonical root email")
        void secretOk_noSuperAdmin_returns201WithCanonicalEmail() throws Exception {
            when(adminUserRepository.countByRole(AdminRole.SUPER_ADMIN))
                    .thenReturn(0L);

            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", VALID_SECRET))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value("root@yadony.test"))
                    .andExpect(jsonPath("$.temporaryPassword").doesNotExist());
        }

        @Test
        @DisplayName("Secret OK, no super-admin → calls the dedicated bootstrap service")
        void secretOk_noSuperAdmin_callsDedicatedBootstrapService() throws Exception {
            when(adminUserRepository.countByRole(AdminRole.SUPER_ADMIN))
                    .thenReturn(0L);

            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", VALID_SECRET))
                    .andExpect(status().isCreated());

            verify(adminAccountService).bootstrapSuperAdmin(
                    "root@yadony.test", "test-only-value");
            verify(adminAccountService, never()).createAdmin(any(), any());
            verify(adminAccountService, never()).resetPassword(any(), any());
        }

        // ── 409 existing root: no break-glass reset ─────────────────────────────

        @Test
        @DisplayName("Secret OK, existing super-admin → 409 without resetting credentials")
        void secretOk_existingSuperAdmin_returns409WithoutReset() throws Exception {
            when(adminUserRepository.countByRole(AdminRole.SUPER_ADMIN))
                    .thenReturn(1L);

            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", VALID_SECRET))
                    .andExpect(status().isConflict());

            verify(adminAccountService, never()).bootstrapSuperAdmin(any(), any());
            verify(adminAccountService, never()).createAdmin(any(), any());
            verify(adminAccountService, never()).resetPassword(any(), any());
        }
    }

    @Nested
    @DisplayName("When the configured bootstrap identity is not the root")
    @SpringBootTest
    @ActiveProfiles("test")
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "yadony.admin.root-email=root@yadony.test",
            "yadony.admin.bootstrap.secret=test-bootstrap-secret-123",
            "yadony.admin.bootstrap.email=other-admin@yadony.com",
            "yadony.admin.bootstrap.password=test-only-value"
    })
    class WhenBootstrapIdentityIsNotRoot {

        @Autowired
        MockMvc mockMvc;

        @MockitoBean
        AdminAccountService adminAccountService;

        @MockitoBean
        AdminUserRepository adminUserRepository;

        @Test
        @DisplayName("The configured identity is refused without creating another SUPER_ADMIN")
        void configuredNonRootEmail_returns403WithoutCreatingSuperAdmin() throws Exception {
            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "test-bootstrap-secret-123"))
                    .andExpect(status().isForbidden());

            verify(adminAccountService, never()).bootstrapSuperAdmin(any(), any());
            verify(adminUserRepository, never()).countByRole(any());
        }
    }

    @Nested
    @DisplayName("When the configured root identity needs normalization")
    @SpringBootTest
    @ActiveProfiles("test")
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "yadony.admin.root-email=root@yadony.test",
            "yadony.admin.bootstrap.secret=test-bootstrap-secret-123",
            "yadony.admin.bootstrap.email= Root@Yadony.Test ",
            "yadony.admin.bootstrap.password=test-only-value"
    })
    class WhenBootstrapIdentityNeedsNormalization {

        @Autowired
        MockMvc mockMvc;

        @MockitoBean
        AdminAccountService adminAccountService;

        @MockitoBean
        AdminUserRepository adminUserRepository;

        @Test
        @DisplayName("The configured root identity is normalized before creation")
        void configuredRootEmail_isNormalizedBeforeCreatingSuperAdmin() throws Exception {
            when(adminUserRepository.countByRole(AdminRole.SUPER_ADMIN)).thenReturn(0L);

            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "test-bootstrap-secret-123"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value("root@yadony.test"))
                    .andExpect(jsonPath("$.temporaryPassword").doesNotExist());

            verify(adminAccountService).bootstrapSuperAdmin(
                    "root@yadony.test", "test-only-value");
        }
    }

    @Nested
    @DisplayName("When no root email is configured")
    @SpringBootTest
    @ActiveProfiles("test")
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "yadony.admin.root-email=",
            "yadony.admin.bootstrap.secret=test-bootstrap-secret-123",
            "yadony.admin.bootstrap.email=root@yadony.test",
            "yadony.admin.bootstrap.password=test-only-value"
    })
    class WhenRootEmailMissing {

        @Autowired
        MockMvc mockMvc;

        @MockitoBean
        AdminAccountService adminAccountService;

        @MockitoBean
        AdminUserRepository adminUserRepository;

        @Test
        @DisplayName("Bootstrap stays disabled (404) even with a valid secret")
        void noRootEmail_returns404() throws Exception {
            mockMvc.perform(post("/admin/bootstrap")
                            .header("X-Bootstrap-Secret", "test-bootstrap-secret-123"))
                    .andExpect(status().isNotFound());

            verify(adminAccountService, never()).bootstrapSuperAdmin(any(), any());
        }
    }
}
