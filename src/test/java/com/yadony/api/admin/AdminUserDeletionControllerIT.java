package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.AdminDeleteUserRequest;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.common.YadonyBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserDeletionControllerIT {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean AdminUserDeletionService deletionService;
    @MockitoBean UserDeletionImpactService impactService;
    @MockitoBean FirebaseContactService firebaseContact;
    @MockitoBean UserRepository userRepository;

    private UsernamePasswordAuthenticationToken auth(AdminRole role) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        var authorities = role.permissions().stream()
                .map(p -> new SimpleGrantedAuthority(p.name()))
                .toList();
        var all = new java.util.ArrayList<SimpleGrantedAuthority>(authorities);
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private String body(String reasonCode, String reason) throws Exception {
        return objectMapper.writeValueAsString(new AdminDeleteUserRequest(reasonCode, reason));
    }

    @Test
    @DisplayName("POST — un administrateur avec USER_DELETE obtient 204 et délègue au service")
    void delete_withPermission_returns204() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/delete", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FRAUD", "faux documents"))
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isNoContent());

        verify(deletionService).delete(eq(USER_ID), eq(ADMIN_ID), eq("FRAUD"), eq("faux documents"));
    }

    @Test
    @DisplayName("POST — le support n'a pas le droit de supprimer un compte")
    void delete_asSupport_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/delete", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FRAUD", "faux documents"))
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verify(deletionService, never()).delete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST — un engagement financier en cours donne 422 en problem+json")
    void delete_whenBlocked_returns422() throws Exception {
        doThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "deletion-blocked",
                "Unprocessable", "Suppression impossible — des engagements financiers sont encore en cours"))
                .when(deletionService).delete(any(), any(), any(), any());

        mockMvc.perform(post("/admin/users/{userId}/delete", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FRAUD", "faux documents"))
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("deletion-blocked"));
    }

    @Test
    @DisplayName("POST — un motif détaillé vide est refusé par la validation (422)")
    void delete_withoutReason_returns422() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/delete", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FRAUD", "  "))
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity());

        verify(deletionService, never()).delete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST — un motif catalogué inconnu est refusé (422)")
    void delete_withUnknownReasonCode_returns422() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/delete", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WHATEVER", "motif"))
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity());
    }

    // Constat 4 : le motif libre doit être borné à 500 caractères pour éviter qu'un
    // texte arbitraire saturant la colonne JSONB immuable ne devienne incontrôlable.
    @Test
    @DisplayName("POST — un motif libre dépassant 500 caractères est refusé (422)")
    void delete_withTooLongReason_returns422() throws Exception {
        String tooLong = "x".repeat(501);
        mockMvc.perform(post("/admin/users/{userId}/delete", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FRAUD", tooLong))
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity());

        verify(deletionService, never()).delete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST — un motif libre de exactement 500 caractères est accepté")
    void delete_withMaxLengthReason_isAccepted() throws Exception {
        String maxLength = "x".repeat(500);
        mockMvc.perform(post("/admin/users/{userId}/delete", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FRAUD", maxLength))
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isNoContent());

        verify(deletionService).delete(eq(USER_ID), eq(ADMIN_ID), eq("FRAUD"), eq(maxLength));
    }

    @Test
    @DisplayName("GET — le rapport d'impact est lisible avec USER_DELETE")
    void impact_withPermission_returns200() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(new UserEntity()));
        when(impactService.report(USER_ID)).thenReturn(
                new com.yadony.api.admin.dto.DeletionImpactResponse(false, List.of()));
        when(firebaseContact.getContacts(anyList())).thenReturn(java.util.Map.of());

        mockMvc.perform(get("/admin/users/{userId}/deletion-impact", USER_ID)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(false));
    }
}
