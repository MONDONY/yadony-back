package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.common.AuditService;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
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

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DELETE /admin/ratings/{id}?reason=...} : le motif (facultatif) atteint l'audit avec
 * l'admin comme acteur ; au-delà de 500 caractères, 400 en problem+json.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminRatingsControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID RATING_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;

    @MockitoBean RatingRepository ratingRepository;
    @MockitoBean AuditService auditService;

    private UsernamePasswordAuthenticationToken auth(AdminRole role) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        role.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    @Test
    @DisplayName("DELETE avec motif — 204, motif et admin dans l'audit")
    void delete_withReason_auditsReasonAndAdmin() throws Exception {
        when(ratingRepository.findById(RATING_ID)).thenReturn(Optional.of(new RatingEntity()));

        mockMvc.perform(delete("/admin/ratings/{id}", RATING_ID)
                        .param("reason", "avis diffamatoire")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isNoContent());

        verify(auditService).log(eq("RATING"), eq(RATING_ID), eq("RATING_DELETED"), eq(ADMIN_ID),
                eq(Map.of("ratingId", RATING_ID.toString(), "reason", "avis diffamatoire")));
    }

    @Test
    @DisplayName("DELETE sans motif — reste accepté (compatibilité du back-office actuel)")
    void delete_withoutReason_stillAccepted() throws Exception {
        when(ratingRepository.findById(RATING_ID)).thenReturn(Optional.of(new RatingEntity()));

        mockMvc.perform(delete("/admin/ratings/{id}", RATING_ID)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isNoContent());

        verify(auditService).log(eq("RATING"), eq(RATING_ID), eq("RATING_DELETED"), eq(ADMIN_ID),
                eq(Map.of("ratingId", RATING_ID.toString(), "reason", "")));
    }

    @Test
    @DisplayName("DELETE motif de 501 caractères — 400 rating-delete-reason-too-long en problem+json")
    void delete_reasonTooLong_returns400Problem() throws Exception {
        mockMvc.perform(delete("/admin/ratings/{id}", RATING_ID)
                        .param("reason", "x".repeat(501))
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("rating-delete-reason-too-long"));

        verify(ratingRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("DELETE — le support (sans RATING_DELETE) reste refusé")
    void delete_asSupport_isForbidden() throws Exception {
        mockMvc.perform(delete("/admin/ratings/{id}", RATING_ID)
                        .param("reason", "x")
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verify(ratingRepository, never()).save(any());
    }

    // ---- Restauration ----

    private RatingEntity deletedRating() {
        RatingEntity r = new RatingEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(r, "id", RATING_ID);
        r.setRatedUserId(UUID.randomUUID());
        r.setBidId(UUID.randomUUID());
        r.setStars(2);
        r.setDeletedAt(java.time.LocalDateTime.now());
        return r;
    }

    @Test
    @DisplayName("POST restore — ADMIN : 200, avis rendu sans deletedAt, audit RATING_RESTORED")
    void restore_asAdmin_returns200() throws Exception {
        when(ratingRepository.findByIdIncludingDeleted(RATING_ID)).thenReturn(Optional.of(deletedRating()));

        mockMvc.perform(post("/admin/ratings/{id}/restore", RATING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Suppression faite par erreur\"}")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(RATING_ID.toString()))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());

        verify(auditService).log(eq("RATING"), eq(RATING_ID), eq("RATING_RESTORED"), eq(ADMIN_ID),
                eq(Map.of("ratingId", RATING_ID.toString(), "reason", "Suppression faite par erreur")));
    }

    @Test
    @DisplayName("POST restore — le support (sans RATING_DELETE) : 403")
    void restore_asSupport_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/ratings/{id}/restore", RATING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Suppression faite par erreur\"}")
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verify(ratingRepository, never()).findByIdIncludingDeleted(any());
    }

    @Test
    @DisplayName("POST restore — avis visible : 409 rating-not-deleted en problem+json")
    void restore_notDeleted_returns409() throws Exception {
        RatingEntity visible = deletedRating();
        visible.setDeletedAt(null);
        when(ratingRepository.findByIdIncludingDeleted(RATING_ID)).thenReturn(Optional.of(visible));

        mockMvc.perform(post("/admin/ratings/{id}/restore", RATING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Suppression faite par erreur\"}")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("rating-not-deleted"));
    }

    @Test
    @DisplayName("POST restore — motif absent ou trop court : 422")
    void restore_shortReason_returns422() throws Exception {
        mockMvc.perform(post("/admin/ratings/{id}/restore", RATING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"oups\"}")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity());

        verify(ratingRepository, never()).findByIdIncludingDeleted(any());
    }

    @Test
    @DisplayName("GET deleted=true — la corbeille, lisible par le support (RATING_MODERATE)")
    void listDeleted_asSupport_returnsTrash() throws Exception {
        when(ratingRepository.findDeletedAdminFiltered(any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(deletedRating()),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/ratings").param("deleted", "true")
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(RATING_ID.toString()))
                .andExpect(jsonPath("$.content[0].deletedAt").exists());

        verify(ratingRepository, never()).findAdminFiltered(any(), any(), any(), any());
    }
}
