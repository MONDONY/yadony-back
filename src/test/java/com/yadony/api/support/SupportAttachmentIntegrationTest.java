package com.yadony.api.support;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'integration pour les endpoints d'upload et les messages avec pieces jointes.
 * StorageService est mocke : pas de S3 reel en test.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SupportAttachmentIntegrationTest {

    private static final String OWNER_UID = "uid-attach-owner";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired SupportTicketRepository ticketRepository;
    @Autowired SupportMessageRepository messageRepository;
    @Autowired SupportMessageAttachmentRepository attachmentRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockBean StorageService storageService;

    private UUID userId;
    private UUID ticketId;
    private AdminUserEntity adminUser;

    @BeforeEach
    void resetData() {
        attachmentRepository.deleteAll();
        messageRepository.deleteAll();
        ticketRepository.deleteAll();
        userRepository.deleteAll();
        adminUserRepository.deleteAll();
        auditLogRepository.deleteAllInBatch();

        // Mock StorageService
        try {
            when(storageService.uploadFile(any(), anyString()))
                    .thenAnswer(inv -> inv.getArgument(1, String.class) + "fake-key.jpg");
        } catch (Exception ignored) {}

        when(storageService.generatePresignedUrl(anyString(), any()))
                .thenReturn("https://fake-s3.yadony.test/photo.jpg");

        UserEntity user = new UserEntity();
        user.setFirebaseUid(OWNER_UID);
        user.setUsername("attach-owner");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.SENDER));
        UserEntity saved = userRepository.save(user);
        userId = saved.getId();

        adminUser = adminUserRepository.save(new AdminUserEntity("uid-admin-attach", "admin-attach@yadony.com", AdminRole.SUPPORT));

        SupportTicketEntity ticket = new SupportTicketEntity();
        ticket.setUserId(userId);
        ticket.setCategory("PAYMENT");
        ticket.setSubject("Photo du recu");
        ticket.setStatus(SupportTicketStatus.ASSIGNED);
        ticket.setAssignedAdminId(adminUser.getId());
        ticket.setLastMessageAt(LocalDateTime.now(ZoneOffset.UTC));
        SupportTicketEntity savedTicket = ticketRepository.save(ticket);
        ticketId = savedTicket.getId();
    }

    @Test
    void acceptsAMessageMadeOfAnImageWithoutAnyText() throws Exception {
        // cle simulee appartenant a l'utilisateur du harnais
        String key = "support/" + userId + "/1_a.jpg";

        mockMvc.perform(post("/support/tickets/" + ticketId + "/messages")
                        .with(authentication(asUser(OWNER_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": null, "attachmentKeys": ["%s"]}
                                """.formatted(key)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments.length()").value(1))
                .andExpect(jsonPath("$.attachments[0].url").exists())
                .andExpect(jsonPath("$.attachments[0].objectKey").doesNotExist());
    }

    @Test
    void refusesAMessageWithNeitherTextNorImage() throws Exception {
        mockMvc.perform(post("/support/tickets/" + ticketId + "/messages")
                        .with(authentication(asUser(OWNER_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"   \", \"attachmentKeys\": []}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void refusesAKeyBelongingToAnotherUser() throws Exception {
        mockMvc.perform(post("/support/tickets/" + ticketId + "/messages")
                        .with(authentication(asUser(OWNER_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "Voici", "attachmentKeys": ["support/%s/1_a.jpg"]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void refusesMoreThanFourImages() throws Exception {
        String keys = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> "\"support/" + userId + "/" + i + "_a.jpg\"")
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/support/tickets/" + ticketId + "/messages")
                        .with(authentication(asUser(OWNER_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Voici\", \"attachmentKeys\": [" + keys + "]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void refusesAnAttachmentUploadFromAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(multipart("/support/attachments")
                        .file(new MockMultipartFile("file", "a.jpg", "image/jpeg",
                                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00})))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTicketCanBeCreatedWithAnAttachmentOnItsFirstMessage() throws Exception {
        String key = "support/" + userId + "/first_a.jpg";
        mockMvc.perform(post("/support/tickets")
                        .with(authentication(asUser(OWNER_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "PAYMENT", "subject": "Photo du recu",
                                 "message": null, "attachmentKeys": ["%s"]}
                                """.formatted(key)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.messages[0].attachments.length()").value(1));
    }

    @Test
    void logsAnAuditEntryWhenAnAdminAttachesAnImage() throws Exception {
        // L'admin répond avec une image
        String key = "support/admin/" + adminUser.getId() + "/1_a.jpg";
        mockMvc.perform(post("/admin/support/tickets/" + ticketId + "/messages")
                        .with(authentication(asAdmin(adminUser)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": null, "attachmentKeys": ["%s"]}
                                """.formatted(key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments.length()").value(1));

        // Vérifier via JDBC pour éviter le problème de désérialisation JSONB sur H2
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'SUPPORT_TICKET_ADMIN_ATTACHED'",
                Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    // ---------------------------------------------------------------- helpers

    private static UsernamePasswordAuthenticationToken asUser(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static UsernamePasswordAuthenticationToken asAdmin(AdminUserEntity admin) {
        AdminPrincipal principal = new AdminPrincipal(
                admin.getId(), admin.getEmail(), admin.getRole(), false, admin.getFirebaseUid());
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("SUPPORT_TICKET_VIEW"),
                        new SimpleGrantedAuthority("SUPPORT_TICKET_MANAGE")));
    }
}
