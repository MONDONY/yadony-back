package com.yadony.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminStartSupportTicketRequest;
import com.yadony.api.admin.dto.ReassignSupportTicketRequest;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.notifications.FcmService;
import com.yadony.api.notifications.NotificationRepository;
import com.yadony.api.support.SupportMessageRepository;
import com.yadony.api.support.SupportTicketEntity;
import com.yadony.api.support.SupportTicketRepository;
import com.yadony.api.support.SupportTicketStatus;
import com.yadony.api.support.dto.CreateSupportMessageRequest;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminSupportControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired SupportTicketRepository ticketRepository;
    @Autowired SupportMessageRepository messageRepository;
    @Autowired NotificationRepository notificationRepository;
    @MockitoBean FcmService fcmService;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private UserEntity user;
    private AdminUserEntity firstAdmin;
    private AdminUserEntity secondAdmin;

    @BeforeEach
    void resetData() {
        notificationRepository.deleteAllInBatch();
        messageRepository.deleteAll();
        ticketRepository.deleteAll();
        userRepository.deleteAll();
        adminUserRepository.deleteAll();
        user = persistUser();
        firstAdmin = persistAdmin("support-1@yadony.com");
        secondAdmin = persistAdmin("support-2@yadony.com");
    }

    @Test
    void list_separatesUnassignedFromMine() throws Exception {
        SupportTicketEntity unassigned = persistTicket(SupportTicketStatus.NEW, null);
        persistTicket(SupportTicketStatus.ASSIGNED, firstAdmin.getId());

        mockMvc.perform(get("/admin/support/tickets")
                        .param("scope", "unassigned")
                        .with(authentication(asAdmin(firstAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(unassigned.getId().toString()));

        mockMvc.perform(get("/admin/support/tickets")
                        .param("scope", "mine")
                        .with(authentication(asAdmin(firstAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].assignedAdminEmail").value("support-1@yadony.com"));
    }

    @Test
    void list_rejectsAnUnknownScope() throws Exception {
        mockMvc.perform(get("/admin/support/tickets")
                        .param("scope", "everything")
                        .with(authentication(asAdmin(firstAdmin))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void list_requiresTheViewPermission() throws Exception {
        mockMvc.perform(get("/admin/support/tickets")
                        .with(authentication(asAdminWithoutSupportPermissions(firstAdmin))))
                .andExpect(status().isForbidden());
    }

    @Test
    void assignThenReplyThenResolve_walksTheWholeFlow() throws Exception {
        SupportTicketEntity ticket = persistTicket(SupportTicketStatus.NEW, null);

        mockMvc.perform(post("/admin/support/tickets/" + ticket.getId() + "/assign")
                        .with(authentication(asAdmin(firstAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.userDisplayName").value("support-user"));

        mockMvc.perform(post("/admin/support/tickets/" + ticket.getId() + "/messages")
                        .with(authentication(asAdmin(firstAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateSupportMessageRequest("On verifie le paiement.", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorType").value("ADMIN"));

        mockMvc.perform(post("/admin/support/tickets/" + ticket.getId() + "/resolve")
                        .with(authentication(asAdmin(firstAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").exists());
    }

    /** Le garde-fou du chantier : deux admins ne repondent pas en parallele. */
    @Test
    void reply_isRefusedToAnAdminWhoDoesNotHoldTheTicket() throws Exception {
        SupportTicketEntity ticket = persistTicket(SupportTicketStatus.ASSIGNED, firstAdmin.getId());

        mockMvc.perform(post("/admin/support/tickets/" + ticket.getId() + "/messages")
                        .with(authentication(asAdmin(secondAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateSupportMessageRequest("Je reprends.", null))))
                .andExpect(status().isConflict());
    }

    @Test
    void reassign_transfersTheTicketToAnotherAdmin() throws Exception {
        SupportTicketEntity ticket = persistTicket(SupportTicketStatus.ASSIGNED, firstAdmin.getId());

        mockMvc.perform(post("/admin/support/tickets/" + ticket.getId() + "/reassign")
                        .with(authentication(asAdmin(firstAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReassignSupportTicketRequest(secondAdmin.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAdminId").value(secondAdmin.getId().toString()))
                .andExpect(jsonPath("$.assignedAdminEmail").value("support-2@yadony.com"));
    }

    @Test
    void reassign_rejectsAnUnknownAdmin() throws Exception {
        SupportTicketEntity ticket = persistTicket(SupportTicketStatus.ASSIGNED, firstAdmin.getId());

        mockMvc.perform(post("/admin/support/tickets/" + ticket.getId() + "/reassign")
                        .with(authentication(asAdmin(firstAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReassignSupportTicketRequest(UUID.randomUUID()))))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------- conversation initiee par l'admin

    @Test
    void start_opensAConversationTheUserSeesInTheSupportInbox() throws Exception {
        String body = objectMapper.writeValueAsString(new AdminStartSupportTicketRequest(
                user.getId(), "kyc", "Votre vérification", "Bonjour, il manque une photo du document.", null));

        String response = mockMvc.perform(post("/admin/support/tickets")
                        .with(authentication(asAdmin(firstAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_USER"))
                .andExpect(jsonPath("$.category").value("KYC"))
                .andExpect(jsonPath("$.subject").value("Votre vérification"))
                .andExpect(jsonPath("$.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.assignedAdminId").value(firstAdmin.getId().toString()))
                .andExpect(jsonPath("$.assignedAdminEmail").value("support-1@yadony.com"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].authorType").value("ADMIN"))
                .andReturn().getResponse().getContentAsString();
        String ticketId = objectMapper.readTree(response).get("id").asText();

        // Push distinct pour le premier message + entree du centre de notifications.
        verify(fcmService, timeout(10_000)).sendToUser(eq(user.getId()), eq("Nouveau message de Yadony"),
                eq("L'équipe Yadony vous a écrit : Votre vérification"), any());
        awaitNotifications(user.getId(), 1);

        mockMvc.perform(get("/support/tickets").with(authentication(asUser(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ticketId))
                .andExpect(jsonPath("$.content[0].unreadCount").value(1))
                .andExpect(jsonPath("$.content[0].lastMessagePreview").value("Bonjour, il manque une photo du document."))
                .andExpect(jsonPath("$.content[0].lastMessageFromAdmin").value(true))
                .andExpect(jsonPath("$.content[0].lastMessageAt").exists());

        mockMvc.perform(get("/support/summary").with(authentication(asUser(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.openTicketCount").value(1))
                .andExpect(jsonPath("$.latestTicket.id").value(ticketId))
                .andExpect(jsonPath("$.latestTicket.unreadCount").value(1))
                .andExpect(jsonPath("$.latestTicket.lastMessageFromAdmin").value(true));

        // L'admin qui a ouvert la conversation y repond sans passer par /assign.
        mockMvc.perform(post("/admin/support/tickets/" + ticketId + "/messages")
                        .with(authentication(asAdmin(firstAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateSupportMessageRequest("Autre precision.", null))))
                .andExpect(status().isOk());
        awaitNotifications(user.getId(), 2);
    }

    @Test
    void start_defaultsTheCategoryToOther() throws Exception {
        mockMvc.perform(post("/admin/support/tickets")
                        .with(authentication(asAdmin(firstAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminStartSupportTicketRequest(
                                user.getId(), null, "Question", "Bonjour", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("OTHER"));
        awaitNotifications(user.getId(), 1);
    }

    @Test
    void start_requiresTheManagePermission() throws Exception {
        mockMvc.perform(post("/admin/support/tickets")
                        .with(authentication(asAdminWithViewOnly(firstAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminStartSupportTicketRequest(
                                user.getId(), "OTHER", "Sujet", "Bonjour", null))))
                .andExpect(status().isForbidden());
        assertThat(ticketRepository.count()).isZero();
    }

    @Test
    void start_validatesTheRequest() throws Exception {
        for (AdminStartSupportTicketRequest invalid : List.of(
                new AdminStartSupportTicketRequest(null, "OTHER", "Sujet", "Bonjour", null),
                new AdminStartSupportTicketRequest(user.getId(), "OTHER", " ", "Bonjour", null),
                new AdminStartSupportTicketRequest(user.getId(), "OTHER", "x".repeat(201), "Bonjour", null),
                new AdminStartSupportTicketRequest(user.getId(), "OTHER", "Sujet", "x".repeat(4001), null),
                new AdminStartSupportTicketRequest(user.getId(), "OTHER", "Sujet", " ", null),
                new AdminStartSupportTicketRequest(user.getId(), "MYSTERE", "Sujet", "Bonjour", null),
                new AdminStartSupportTicketRequest(user.getId(), "OTHER", "Sujet", "Bonjour",
                        List.of("support/user/" + user.getId() + "/photo.jpg")))) {
            mockMvc.perform(post("/admin/support/tickets")
                            .with(authentication(asAdmin(firstAdmin)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isUnprocessableEntity());
        }
        assertThat(ticketRepository.count()).isZero();
    }

    @Test
    void start_returns404ForAnUnknownUser() throws Exception {
        mockMvc.perform(post("/admin/support/tickets")
                        .with(authentication(asAdmin(firstAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminStartSupportTicketRequest(
                                UUID.randomUUID(), "OTHER", "Sujet", "Bonjour", null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
    }

    @Test
    void list_filtersByUser_andCombinesWithStatus() throws Exception {
        UserEntity other = persistUser("uid-support-other", "other-user");
        SupportTicketEntity mineNew = persistTicket(SupportTicketStatus.NEW, null);
        SupportTicketEntity mineResolved = persistTicket(SupportTicketStatus.RESOLVED, firstAdmin.getId());
        SupportTicketEntity foreign = persistTicket(SupportTicketStatus.NEW, null);
        foreign.setUserId(other.getId());
        ticketRepository.save(foreign);

        mockMvc.perform(get("/admin/support/tickets")
                        .param("userId", user.getId().toString())
                        .with(authentication(asAdmin(firstAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[?(@.id == '" + foreign.getId() + "')]").isEmpty());

        mockMvc.perform(get("/admin/support/tickets")
                        .param("userId", user.getId().toString())
                        .param("status", "resolved")
                        .with(authentication(asAdmin(firstAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(mineResolved.getId().toString()));

        // userId prime sur le scope : la fiche utilisateur veut tout son historique.
        mockMvc.perform(get("/admin/support/tickets")
                        .param("userId", user.getId().toString())
                        .param("scope", "unassigned")
                        .with(authentication(asAdmin(firstAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
        assertThat(mineNew.getId()).isNotNull();
    }

    /** Chaque message admin : une entree in-app et un push ; le message de l'utilisateur, rien pour lui. */
    @Test
    void adminReplyCreatesOneInAppNotificationAndOnePush_userMessageNone() throws Exception {
        SupportTicketEntity ticket = persistTicket(SupportTicketStatus.WAITING_SUPPORT, firstAdmin.getId());

        mockMvc.perform(post("/support/tickets/" + ticket.getId() + "/messages")
                        .with(authentication(asUser(user)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateSupportMessageRequest("Toujours bloque", null))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/admin/support/tickets/" + ticket.getId() + "/messages")
                        .with(authentication(asAdmin(firstAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateSupportMessageRequest("On verifie.", null))))
                .andExpect(status().isOk());

        verify(fcmService, timeout(10_000)).sendToUser(eq(user.getId()), eq("Le support vous a répondu"),
                anyString(), any());
        awaitNotifications(user.getId(), 1);
        Thread.sleep(300);
        verify(fcmService, org.mockito.Mockito.times(1)).sendToUser(any(), anyString(), anyString(), any());
        // findAll() relirait la colonne JSONB, que H2 ne sait pas relire : SQL direct.
        assertThat(jdbcTemplate.queryForList("SELECT type FROM notifications", String.class))
                .containsExactly("SUPPORT_MESSAGE");
        assertThat(jdbcTemplate.queryForList(
                "SELECT CAST(user_id AS VARCHAR) FROM notifications", String.class))
                .containsExactly(user.getId().toString());
    }

    // ---------------------------------------------------------------- helpers

    private void awaitNotifications(UUID userId, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (notificationRepository.countByUserIdAndReadAtIsNull(userId) < expected
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(notificationRepository.countByUserIdAndReadAtIsNull(userId)).isEqualTo(expected);
    }

    private static UsernamePasswordAuthenticationToken asUser(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(
                user.getFirebaseUid(), null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static UsernamePasswordAuthenticationToken asAdminWithViewOnly(AdminUserEntity admin) {
        return new UsernamePasswordAuthenticationToken(
                principalOf(admin), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("SUPPORT_TICKET_VIEW")));
    }

    private static UsernamePasswordAuthenticationToken asAdmin(AdminUserEntity admin) {
        return new UsernamePasswordAuthenticationToken(
                principalOf(admin), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("SUPPORT_TICKET_VIEW"),
                        new SimpleGrantedAuthority("SUPPORT_TICKET_MANAGE")));
    }

    private static UsernamePasswordAuthenticationToken asAdminWithoutSupportPermissions(AdminUserEntity admin) {
        return new UsernamePasswordAuthenticationToken(
                principalOf(admin), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static AdminPrincipal principalOf(AdminUserEntity admin) {
        return new AdminPrincipal(admin.getId(), admin.getEmail(), admin.getRole(), false, admin.getFirebaseUid());
    }

    private UserEntity persistUser() {
        return persistUser("uid-support-user", "support-user");
    }

    private UserEntity persistUser(String firebaseUid, String username) {
        UserEntity entity = new UserEntity();
        entity.setFirebaseUid(firebaseUid);
        entity.setUsername(username);
        entity.setStatus(UserStatus.ACTIVE);
        entity.setRoles(Set.of(Role.SENDER));
        return userRepository.save(entity);
    }

    private AdminUserEntity persistAdmin(String email) {
        return adminUserRepository.save(
                new AdminUserEntity("uid-" + email, email, AdminRole.SUPPORT));
    }

    private SupportTicketEntity persistTicket(SupportTicketStatus status, UUID assignedAdminId) {
        SupportTicketEntity ticket = new SupportTicketEntity();
        ticket.setUserId(user.getId());
        ticket.setCategory("PAYMENT");
        ticket.setSubject("Paiement bloque");
        ticket.setStatus(status);
        ticket.setAssignedAdminId(assignedAdminId);
        ticket.setLastMessageAt(LocalDateTime.now(ZoneOffset.UTC));
        return ticketRepository.save(ticket);
    }
}
