package com.yadony.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.ReassignSupportTicketRequest;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    private UserEntity user;
    private AdminUserEntity firstAdmin;
    private AdminUserEntity secondAdmin;

    @BeforeEach
    void resetData() {
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

    // ---------------------------------------------------------------- helpers

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
        UserEntity entity = new UserEntity();
        entity.setFirebaseUid("uid-support-user");
        entity.setUsername("support-user");
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
