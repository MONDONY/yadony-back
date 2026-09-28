package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserService;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Annulation d'une suppression de compte par un admin, et file des comptes en suppression. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserCancelDeletionIT {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String BODY = "{\"reason\":\"Demande faite par erreur\"}";

    @Autowired MockMvc mockMvc;

    @MockitoBean UserService userService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean FirebaseContactService firebaseContact;

    private UsernamePasswordAuthenticationToken auth(AdminRole role) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        role.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private static UserEntity user(UserStatus status) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(status);
        u.setKycStatus(KycStatus.NOT_STARTED);
        return u;
    }

    @Test
    @DisplayName("POST cancel-deletion — ADMIN : 200, détail ACTIVE, admin et motif transmis")
    void cancel_asAdmin_returns200() throws Exception {
        when(firebaseContact.getContact(any())).thenReturn(FirebaseContactService.Contact.EMPTY);
        when(userService.cancelDeletionByAdmin(USER_ID, ADMIN_ID, "Demande faite par erreur"))
                .thenReturn(user(UserStatus.ACTIVE));

        mockMvc.perform(post("/admin/users/{id}/cancel-deletion", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.deletionRequestedAt").doesNotExist())
                .andExpect(jsonPath("$.deletionScheduledFor").doesNotExist());
    }

    @Test
    @DisplayName("POST cancel-deletion — le support (sans USER_DELETE) : 403")
    void cancel_asSupport_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/cancel-deletion", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verify(userService, never()).cancelDeletionByAdmin(any(), any(), any());
    }

    @Test
    @DisplayName("POST cancel-deletion — compte non annulable : 409 user-deletion-not-cancellable")
    void cancel_notCancellable_returns409() throws Exception {
        when(userService.cancelDeletionByAdmin(eq(USER_ID), eq(ADMIN_ID), any())).thenThrow(
                new YadonyBusinessException(HttpStatus.CONFLICT, "user-deletion-not-cancellable",
                        "Conflict", "non annulable"));

        mockMvc.perform(post("/admin/users/{id}/cancel-deletion", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user-deletion-not-cancellable"));
    }

    @Test
    @DisplayName("POST cancel-deletion — motif trop court : 422")
    void cancel_shortReason_returns422() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/cancel-deletion", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"court\"}")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity());

        verify(userService, never()).cancelDeletionByAdmin(any(), any(), any());
    }

    @Test
    @DisplayName("GET ?status=PENDING_DELETION — filtre transmis, dates de suppression exposées")
    void list_pendingDeletion_filtersAndExposesDates() throws Exception {
        UserEntity pending = user(UserStatus.PENDING_DELETION);
        pending.setDeletionRequestedAt(Instant.parse("2026-09-10T08:00:00Z"));
        when(userRepository.findAdminFiltered(eq("PENDING_DELETION"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(pending), PageRequest.of(0, 20), 1));
        when(firebaseContact.getContacts(any())).thenReturn(Map.of());

        mockMvc.perform(get("/admin/users").param("status", "PENDING_DELETION")
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PENDING_DELETION"))
                .andExpect(jsonPath("$.content[0].deletionRequestedAt").value("2026-09-10T08:00:00Z"))
                .andExpect(jsonPath("$.content[0].deletionScheduledFor").value("2026-10-10T08:00:00Z"));
    }
}
