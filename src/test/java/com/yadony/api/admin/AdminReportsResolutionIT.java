package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.common.AuditService;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Résolution d'un signalement : permissions par action, cible introuvable, forme JSON. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminReportsResolutionIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID REPORT_ID = UUID.randomUUID();
    private static final UUID RATING_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;

    @MockitoBean ReportRepository reportRepository;
    @MockitoBean RatingRepository ratingRepository;
    @MockitoBean AuditService auditService;

    private UsernamePasswordAuthenticationToken auth(AdminRole role) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        role.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private UsernamePasswordAuthenticationToken lectureSeule() {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", AdminRole.SUPPORT, false, "uid");
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(
                new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("REPORT_VIEW")));
    }

    private ReportEntity report(ReportTargetType type, UUID targetId) {
        ReportEntity r = new ReportEntity();
        ReflectionTestUtils.setField(r, "id", REPORT_ID);
        r.setTargetType(type);
        r.setTargetId(targetId);
        r.setReason(ReportReason.OTHER);
        r.setStatus(ReportStatus.OPEN);
        return r;
    }

    private void avisSignale() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report(ReportTargetType.RATING, RATING_ID)));
        RatingEntity rating = new RatingEntity();
        ReflectionTestUtils.setField(rating, "id", RATING_ID);
        rating.setTrackingToken("tok"); // avis anonyme du destinataire : pas d'auteur
        when(ratingRepository.findAllById(anyCollection())).thenReturn(List.of(rating));
    }

    private static String body(String action) {
        return "{\"action\":\"" + action + "\",\"note\":\"vu\"}";
    }

    @Test
    @DisplayName("GET détail — ADMIN : actions de l'avis, pas d'auteur pour un avis anonyme")
    void detail_admin_actionsDeLAvis() throws Exception {
        avisSignale();

        mockMvc.perform(get("/admin/reports/{id}", REPORT_ID).with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableActions",
                        contains("RESOLVE", "DISMISS", "EXCLUDE_RATING", "DELETE_RATING")))
                .andExpect(jsonPath("$.targetAuthor").doesNotExist());
    }

    @Test
    @DisplayName("GET détail — SUPPORT : ni suppression d'avis (RATING_DELETE) proposée")
    void detail_support_sansSuppression() throws Exception {
        avisSignale();

        mockMvc.perform(get("/admin/reports/{id}", REPORT_ID).with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableActions", contains("RESOLVE", "DISMISS", "EXCLUDE_RATING")));
    }

    @Test
    @DisplayName("GET liste — chaque signalement porte ses actions")
    void liste_portesLesActions() throws Exception {
        when(reportRepository.findFiltered(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(report(ReportTargetType.APP, null)),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/reports").with(authentication(lectureSeule())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].availableActions", empty()));

        mockMvc.perform(get("/admin/reports").with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].availableActions", contains("RESOLVE", "DISMISS")));
    }

    @Test
    @DisplayName("POST resolve DELETE_RATING — SUPPORT sans RATING_DELETE : 403")
    void deleteRating_support_403() throws Exception {
        avisSignale();

        mockMvc.perform(post("/admin/reports/{id}/resolve", REPORT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body("DELETE_RATING"))
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("authority-required"));

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST resolve REMOVE_CONTENT — SUPPORT sans CONTENT_REMOVE : 403")
    void removeContent_support_403() throws Exception {
        when(reportRepository.findById(REPORT_ID))
                .thenReturn(Optional.of(report(ReportTargetType.ANNOUNCEMENT, UUID.randomUUID())));

        mockMvc.perform(post("/admin/reports/{id}/resolve", REPORT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body("REMOVE_CONTENT"))
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("authority-required"));
    }

    @Test
    @DisplayName("POST resolve — sans REPORT_RESOLVE : 403 avant toute lecture")
    void resolve_sansReportResolve_403() throws Exception {
        mockMvc.perform(post("/admin/reports/{id}/resolve", REPORT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body("RESOLVE"))
                        .with(authentication(lectureSeule())))
                .andExpect(status().isForbidden());

        verify(reportRepository, never()).findById(any());
    }

    @Test
    @DisplayName("POST resolve DELETE_MESSAGE — message introuvable : 422 report-target-unresolvable")
    void deleteMessage_introuvable_422() throws Exception {
        when(reportRepository.findById(REPORT_ID))
                .thenReturn(Optional.of(report(ReportTargetType.MESSAGE, UUID.randomUUID())));

        mockMvc.perform(post("/admin/reports/{id}/resolve", REPORT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body("DELETE_MESSAGE"))
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("report-target-unresolvable"));

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST resolve RESOLVE — rapport du scarabée : RESOLVED, plus aucune action")
    void resolve_rapportApp_200() throws Exception {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report(ReportTargetType.APP, null)));

        mockMvc.perform(post("/admin/reports/{id}/resolve", REPORT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body("RESOLVE"))
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.actionTaken").value("RESOLVE"))
                .andExpect(jsonPath("$.availableActions", empty()));
    }
}
