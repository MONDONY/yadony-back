package com.yadony.api.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.support.dto.CreateSupportMessageRequest;
import com.yadony.api.support.dto.CreateSupportTicketRequest;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SupportControllerIntegrationTest {

    private static final String OWNER_UID = "uid-support-owner";
    private static final String INTRUDER_UID = "uid-support-intruder";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired SupportTicketRepository ticketRepository;
    @Autowired SupportMessageRepository messageRepository;
    @Autowired SupportPredefinedReplyRepository replyRepository;

    private UserEntity owner;
    private UserEntity intruder;

    @BeforeEach
    void resetData() {
        messageRepository.deleteAll();
        ticketRepository.deleteAll();
        replyRepository.deleteAll();
        userRepository.deleteAll();
        owner = persistUser(OWNER_UID, "owner");
        intruder = persistUser(INTRUDER_UID, "intruder");
        replyRepository.save(reply("payment-refund", 10, true));
        replyRepository.save(reply("archived-answer", 20, false));
    }

    @Test
    void listReplies_exposesOnlyActiveEntries() throws Exception {
        mockMvc.perform(get("/support/replies").with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("payment-refund"));
    }

    @Test
    void createTicket_returnsTheThreadWithItsFirstMessage() throws Exception {
        mockMvc.perform(post("/support/tickets")
                        .with(authentication(asUser(OWNER_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSupportTicketRequest(
                                "PAYMENT", "Paiement bloque", "Je ne vois pas le remboursement."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.subject").value("Paiement bloque"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].authorType").value("USER"));
    }

    @Test
    void createTicket_rejectsUnknownCategory() throws Exception {
        mockMvc.perform(post("/support/tickets")
                        .with(authentication(asUser(OWNER_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSupportTicketRequest(
                                "MYSTERE", "Sujet", "Message"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void listTickets_isScopedToTheCaller() throws Exception {
        persistTicket(owner, SupportTicketStatus.NEW);
        persistTicket(intruder, SupportTicketStatus.NEW);

        mockMvc.perform(get("/support/tickets").with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    /** 404 et non 403 : un 403 confirmerait au demandeur que le ticket existe. */
    @Test
    void getTicket_hidesTicketsOfOtherUsers() throws Exception {
        SupportTicketEntity foreign = persistTicket(intruder, SupportTicketStatus.NEW);

        mockMvc.perform(get("/support/tickets/" + foreign.getId())
                        .with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isNotFound());
    }

    @Test
    void addMessage_isRefusedOnAResolvedTicket() throws Exception {
        SupportTicketEntity resolved = persistTicket(owner, SupportTicketStatus.RESOLVED);

        mockMvc.perform(post("/support/tickets/" + resolved.getId() + "/messages")
                        .with(authentication(asUser(OWNER_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateSupportMessageRequest("Toujours bloque"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void addMessage_movesTheTicketBackToTheSupportQueue() throws Exception {
        SupportTicketEntity ticket = persistTicket(owner, SupportTicketStatus.WAITING_USER);

        mockMvc.perform(post("/support/tickets/" + ticket.getId() + "/messages")
                        .with(authentication(asUser(OWNER_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateSupportMessageRequest("Toujours bloque"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorType").value("USER"));

        mockMvc.perform(get("/support/tickets/" + ticket.getId())
                        .with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_SUPPORT"));
    }

    // ---------------------------------------------------------------- helpers

    private static UsernamePasswordAuthenticationToken asUser(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private UserEntity persistUser(String firebaseUid, String username) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setUsername(username);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.SENDER));
        return userRepository.save(user);
    }

    private SupportTicketEntity persistTicket(UserEntity user, SupportTicketStatus status) {
        SupportTicketEntity ticket = new SupportTicketEntity();
        ticket.setUserId(user.getId());
        ticket.setCategory("PAYMENT");
        ticket.setSubject("Paiement bloque");
        ticket.setStatus(status);
        ticket.setLastMessageAt(LocalDateTime.now(ZoneOffset.UTC));
        return ticketRepository.save(ticket);
    }

    private static SupportPredefinedReplyEntity reply(String code, int sortOrder, boolean active) {
        SupportPredefinedReplyEntity reply = new SupportPredefinedReplyEntity();
        reply.setCode(code);
        reply.setCategory("PAYMENT");
        reply.setQuestion("Quand arrive mon remboursement ?");
        reply.setAnswer("Comptez 5 a 10 jours ouvres.");
        reply.setSortOrder(sortOrder);
        reply.setActive(active);
        return reply;
    }
}
