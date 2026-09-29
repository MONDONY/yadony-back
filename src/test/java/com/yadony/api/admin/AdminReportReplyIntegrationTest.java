package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.notifications.FcmService;
import com.yadony.api.notifications.NotificationRepository;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportPhotoEntity;
import com.yadony.api.signalements.ReportPhotoRepository;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import com.yadony.api.support.SupportMessageAttachmentRepository;
import com.yadony.api.support.SupportMessageRepository;
import com.yadony.api.support.SupportTicketEntity;
import com.yadony.api.support.SupportTicketRepository;
import com.yadony.api.support.SupportTicketStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /admin/reports/{id}/reply : l'admin répond au signalant d'un bug du scarabée dans
 * une conversation support. StorageService est mocké (pas de R2 en test).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminReportReplyIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired ReportRepository reportRepository;
    @Autowired ReportPhotoRepository photoRepository;
    @Autowired SupportTicketRepository ticketRepository;
    @Autowired SupportMessageRepository messageRepository;
    @Autowired SupportMessageAttachmentRepository attachmentRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean FcmService fcmService;
    @MockitoBean StorageService storageService;

    private UserEntity reporter;
    private AdminUserEntity admin;

    @BeforeEach
    void resetData() {
        notificationRepository.deleteAllInBatch();
        attachmentRepository.deleteAll();
        messageRepository.deleteAll();
        photoRepository.deleteAll();
        reportRepository.deleteAll();
        ticketRepository.deleteAll();
        userRepository.deleteAll();
        adminUserRepository.deleteAll();
        auditLogRepository.deleteAllInBatch();

        when(storageService.copyObject(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1, String.class) + "copy_" + UUID.randomUUID() + ".png");
        when(storageService.generatePresignedUrl(anyString(), any()))
                .thenAnswer(inv -> "https://signed.test/" + inv.getArgument(0, String.class));

        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-reporter");
        user.setUsername("reporter");
        user.setFirstName("Awa");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.SENDER));
        reporter = userRepository.save(user);
        admin = adminUserRepository.save(new AdminUserEntity("uid-admin-reply", "reply@yadony.com", AdminRole.SUPPORT));
    }

    @Test
    void reply_opensAConversationWithTheContextThenTheReply_oneSinglePush() throws Exception {
        ReportEntity report = persistAppReport(reporter.getId(), "Le bouton ne répond pas", "/profile/edit");
        persistPhoto(report, "reports/" + reporter.getId() + "/a.png");
        persistPhoto(report, "reports/" + reporter.getId() + "/b.png");

        String body = mockMvc.perform(post("/admin/reports/{id}/reply", report.getId())
                        .with(authentication(asAdmin(admin, "REPORT_VIEW", "SUPPORT_TICKET_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Merci, c'est corrigé dans la prochaine version.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.ticket.status").value("WAITING_USER"))
                .andExpect(jsonPath("$.ticket.category").value("OTHER"))
                .andExpect(jsonPath("$.ticket.userId").value(reporter.getId().toString()))
                .andExpect(jsonPath("$.ticket.assignedAdminId").value(admin.getId().toString()))
                .andExpect(jsonPath("$.ticket.sourceReportId").value(report.getId().toString()))
                .andExpect(jsonPath("$.ticket.messages.length()").value(2))
                .andExpect(jsonPath("$.ticket.messages[0].authorType").value("ADMIN"))
                .andExpect(jsonPath("$.ticket.messages[0].attachments.length()").value(2))
                .andExpect(jsonPath("$.ticket.messages[1].authorType").value("ADMIN"))
                .andExpect(jsonPath("$.ticket.messages[1].content")
                        .value("Merci, c'est corrigé dans la prochaine version."))
                .andExpect(jsonPath("$.ticket.messages[1].attachments.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        UUID ticketId = UUID.fromString(json.get("ticketId").asText());
        assertThat(json.get("ticket").get("id").asText()).isEqualTo(ticketId.toString());
        assertThat(json.get("ticket").get("subject").asText()).startsWith("Votre signalement du ");
        String context = json.get("ticket").get("messages").get(0).get("content").asText();
        assertThat(context).startsWith("Votre signalement :")
                .contains("Le bouton ne répond pas")
                .contains("Écran concerné : Profil (/profile/edit)");
        // Les captures sont des copies sous le préfixe support du signalant, présignées.
        assertThat(json.get("ticket").get("messages").get(0).get("attachments").get(0).get("url").asText())
                .startsWith("https://signed.test/support/" + reporter.getId() + "/");
        verify(storageService).copyObject("reports/" + reporter.getId() + "/a.png", "support/" + reporter.getId() + "/");
        verify(storageService).copyObject("reports/" + reporter.getId() + "/b.png", "support/" + reporter.getId() + "/");

        // Signalement lié, statut inchangé, audit posé.
        ReportEntity reloaded = reportRepository.findById(report.getId()).orElseThrow();
        assertThat(reloaded.getSupportTicketId()).isEqualTo(ticketId);
        assertThat(reloaded.getStatus()).isEqualTo(ReportStatus.OPEN);
        assertThat(reloaded.getResolvedAt()).isNull();
        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM audit_log WHERE entity_id = ?", String.class, report.getId()))
                .containsExactly("REPORT_REPLIED");

        // Une seule notification : « Nouveau message de Yadony », pas une par message.
        verify(fcmService, timeout(10_000)).sendToUser(eq(reporter.getId()), eq("Nouveau message de Yadony"),
                anyString(), any());
        awaitNotifications(reporter.getId(), 1);
        Thread.sleep(300);
        verify(fcmService, times(1)).sendToUser(any(), anyString(), anyString(), any());
        assertThat(notificationRepository.countByUserIdAndReadAtIsNull(reporter.getId())).isEqualTo(1);

        // Le lien inverse est visible depuis la page Support.
        mockMvc.perform(get("/admin/support/tickets/{id}", ticketId)
                        .with(authentication(asAdmin(admin, "SUPPORT_TICKET_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceReportId").value(report.getId().toString()));
        mockMvc.perform(get("/admin/support/tickets")
                        .with(authentication(asAdmin(admin, "SUPPORT_TICKET_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sourceReportId").value(report.getId().toString()));

        // Et depuis la liste des signalements.
        mockMvc.perform(get("/admin/reports")
                        .with(authentication(asAdmin(admin, "REPORT_VIEW", "SUPPORT_TICKET_MANAGE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].supportTicketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.content[0].canReply").value(true));
    }

    @Test
    void reply_onAnOpenLinkedTicket_addsTheReplyToIt_andTakesItOver() throws Exception {
        ReportEntity report = persistAppReport(reporter.getId(), "Bug", "/home");
        UUID ticketId = firstReply(report);
        AdminUserEntity colleague = adminUserRepository.save(
                new AdminUserEntity("uid-colleague", "colleague@yadony.com", AdminRole.SUPPORT));

        mockMvc.perform(post("/admin/reports/{id}/reply", report.getId())
                        .with(authentication(asAdmin(colleague, "REPORT_VIEW", "SUPPORT_TICKET_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Un complément.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.ticket.assignedAdminId").value(colleague.getId().toString()))
                .andExpect(jsonPath("$.ticket.messages.length()").value(3))
                .andExpect(jsonPath("$.ticket.messages[2].content").value("Un complément."));
        assertThat(ticketRepository.count()).isEqualTo(1);
    }

    @Test
    void reply_onAResolvedLinkedTicket_opensANewOne() throws Exception {
        ReportEntity report = persistAppReport(reporter.getId(), "Bug", "/home");
        UUID first = firstReply(report);
        SupportTicketEntity resolved = ticketRepository.findById(first).orElseThrow();
        resolved.setStatus(SupportTicketStatus.RESOLVED);
        ticketRepository.save(resolved);

        String body = mockMvc.perform(post("/admin/reports/{id}/reply", report.getId())
                        .with(authentication(asAdmin(admin, "REPORT_VIEW", "SUPPORT_TICKET_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Ça revient ?\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.ticket.messages.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        UUID second = UUID.fromString(objectMapper.readTree(body).get("ticketId").asText());
        assertThat(second).isNotEqualTo(first);
        assertThat(reportRepository.findById(report.getId()).orElseThrow().getSupportTicketId()).isEqualTo(second);
    }

    @Test
    void reply_onANonAppReport_is422() throws Exception {
        ReportEntity report = persistAppReport(reporter.getId(), "Profil suspect", null);
        report.setTargetType(ReportTargetType.USER);
        report.setTargetId(UUID.randomUUID());
        report.setReason(ReportReason.OTHER);
        reportRepository.save(report);

        mockMvc.perform(post("/admin/reports/{id}/reply", report.getId())
                        .with(authentication(asAdmin(admin, "REPORT_VIEW", "SUPPORT_TICKET_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("report-not-app-bug"));
        assertThat(ticketRepository.count()).isZero();
    }

    @Test
    void reply_whenTheReporterIsDeleted_is422() throws Exception {
        // Compte à part, identifiants uniques : deleteAll() passe par le @Where et laisserait
        // la ligne supprimée (soft delete) d'un run précédent.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserEntity gone = new UserEntity();
        gone.setFirebaseUid("uid-gone-" + suffix);
        gone.setUsername("gone-" + suffix);
        gone.setStatus(UserStatus.ACTIVE);
        gone.setRoles(Set.of(Role.SENDER));
        gone = userRepository.save(gone);
        ReportEntity report = persistAppReport(gone.getId(), "Bug", "/home");
        gone.softDelete();
        userRepository.save(gone);

        mockMvc.perform(post("/admin/reports/{id}/reply", report.getId())
                        .with(authentication(asAdmin(admin, "REPORT_VIEW", "SUPPORT_TICKET_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("reporter-unavailable"));

        mockMvc.perform(get("/admin/reports/{id}", report.getId())
                        .with(authentication(asAdmin(admin, "REPORT_VIEW", "SUPPORT_TICKET_MANAGE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canReply").value(false));
    }

    @Test
    void reply_unknownReport_is404() throws Exception {
        mockMvc.perform(post("/admin/reports/{id}/reply", UUID.randomUUID())
                        .with(authentication(asAdmin(admin, "REPORT_VIEW", "SUPPORT_TICKET_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("report-not-found"));
    }

    @Test
    void reply_emptyMessage_is422() throws Exception {
        ReportEntity report = persistAppReport(reporter.getId(), "Bug", "/home");

        mockMvc.perform(post("/admin/reports/{id}/reply", report.getId())
                        .with(authentication(asAdmin(admin, "REPORT_VIEW", "SUPPORT_TICKET_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());
        verify(storageService, never()).copyObject(anyString(), anyString());
    }

    @Test
    void reply_requiresBothPermissions() throws Exception {
        ReportEntity report = persistAppReport(reporter.getId(), "Bug", "/home");

        mockMvc.perform(post("/admin/reports/{id}/reply", report.getId())
                        .with(authentication(asAdmin(admin, "REPORT_VIEW")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/reports/{id}/reply", report.getId())
                        .with(authentication(asAdmin(admin, "SUPPORT_TICKET_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"x\"}"))
                .andExpect(status().isForbidden());
        assertThat(ticketRepository.count()).isZero();
    }

    @Test
    void list_canReplyDependsOnTargetAndCallerPermissions() throws Exception {
        persistAppReport(reporter.getId(), "Bug", "/home");

        mockMvc.perform(get("/admin/reports")
                        .with(authentication(asAdmin(admin, "REPORT_VIEW", "SUPPORT_TICKET_MANAGE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].canReply").value(true))
                .andExpect(jsonPath("$.content[0].supportTicketId").doesNotExist());
        mockMvc.perform(get("/admin/reports")
                        .with(authentication(asAdmin(admin, "REPORT_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].canReply").value(false));
    }

    // ---------------------------------------------------------------- helpers

    private UUID firstReply(ReportEntity report) throws Exception {
        String body = mockMvc.perform(post("/admin/reports/{id}/reply", report.getId())
                        .with(authentication(asAdmin(admin, "REPORT_VIEW", "SUPPORT_TICKET_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Première réponse\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("ticketId").asText());
    }

    private void awaitNotifications(UUID userId, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (notificationRepository.countByUserIdAndReadAtIsNull(userId) < expected
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(notificationRepository.countByUserIdAndReadAtIsNull(userId)).isEqualTo(expected);
    }

    private ReportEntity persistAppReport(UUID reporterId, String description, String screenRoute) {
        ReportEntity report = new ReportEntity();
        report.setTargetType(ReportTargetType.APP);
        report.setReason(ReportReason.SCREEN_BUG);
        report.setReporterId(reporterId);
        report.setDescription(description);
        report.setScreenRoute(screenRoute);
        report.setStatus(ReportStatus.OPEN);
        return reportRepository.save(report);
    }

    private void persistPhoto(ReportEntity report, String key) {
        ReportPhotoEntity photo = new ReportPhotoEntity();
        photo.setReportId(report.getId());
        photo.setObjectKey(key);
        photoRepository.save(photo);
    }

    private static UsernamePasswordAuthenticationToken asAdmin(AdminUserEntity admin, String... permissions) {
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            authorities.add(new SimpleGrantedAuthority(p));
        }
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(admin.getId(), admin.getEmail(), admin.getRole(), false, admin.getFirebaseUid()),
                null, authorities);
    }
}
