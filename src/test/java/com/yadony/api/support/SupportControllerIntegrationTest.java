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
import org.springframework.http.HttpHeaders;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    void listReplies_withoutAcceptLanguageHeader_rendsFrench() throws Exception {
        mockMvc.perform(get("/support/replies").with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].question").value("Quand arrive mon remboursement ?"))
                .andExpect(jsonPath("$[0].answer").value("Comptez 5 a 10 jours ouvres."));
    }

    @Test
    void listReplies_withEnglishAcceptLanguageHeader_rendsEnglish() throws Exception {
        mockMvc.perform(get("/support/replies")
                        .with(authentication(asUser(OWNER_UID)))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].question").value("When will my refund arrive?"))
                .andExpect(jsonPath("$[0].answer").value("Within 5 to 10 business days."));
    }

    /** Une ligne sans traduction (V265 nullable) retombe sur le francais meme en anglais. */
    @Test
    void listReplies_withEnglishHeaderButNoTranslation_fallsBackToFrench() throws Exception {
        replyRepository.deleteAll();
        SupportPredefinedReplyEntity untranslated = reply("delivery-untranslated", 10, true);
        untranslated.setQuestionEn(null);
        untranslated.setAnswerEn(null);
        replyRepository.save(untranslated);

        mockMvc.perform(get("/support/replies")
                        .with(authentication(asUser(OWNER_UID)))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].question").value("Quand arrive mon remboursement ?"))
                .andExpect(jsonPath("$[0].answer").value("Comptez 5 a 10 jours ouvres."));
    }

    @Test
    void createTicket_returnsTheThreadWithItsFirstMessage() throws Exception {
        mockMvc.perform(post("/support/tickets")
                        .with(authentication(asUser(OWNER_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSupportTicketRequest(
                                "PAYMENT", "Paiement bloque", "Je ne vois pas le remboursement.", null))))
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
                                "MYSTERE", "Sujet", "Message", null))))
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

    // ------------------------------------------------ masquer un ticket (FLUTTER-9W)

    @Test
    void deleteTicket_hidesAResolvedTicketFromTheUsersInboxOnly() throws Exception {
        SupportTicketEntity resolved = persistTicket(owner, SupportTicketStatus.RESOLVED);
        SupportTicketEntity open = persistTicket(owner, SupportTicketStatus.WAITING_USER);

        mockMvc.perform(delete("/support/tickets/" + resolved.getId())
                        .with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/support/tickets").with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(open.getId().toString()));
        // Jamais supprimé : le ticket reste en base pour le back-office.
        org.assertj.core.api.Assertions.assertThat(ticketRepository.findById(resolved.getId()))
                .get()
                .extracting(SupportTicketEntity::getHiddenByUserAt)
                .isNotNull();
    }

    @Test
    void deleteTicket_refusesATicketStillInProgress() throws Exception {
        SupportTicketEntity open = persistTicket(owner, SupportTicketStatus.WAITING_SUPPORT);

        mockMvc.perform(delete("/support/tickets/" + open.getId())
                        .with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("support-ticket-not-resolved"));
    }

    @Test
    void deleteTicket_returns404ForSomeoneElsesTicket() throws Exception {
        SupportTicketEntity foreign = persistTicket(intruder, SupportTicketStatus.RESOLVED);

        mockMvc.perform(delete("/support/tickets/" + foreign.getId())
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
                                new CreateSupportMessageRequest("Toujours bloque", null))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void addMessage_movesTheTicketBackToTheSupportQueue() throws Exception {
        SupportTicketEntity ticket = persistTicket(owner, SupportTicketStatus.WAITING_USER);

        mockMvc.perform(post("/support/tickets/" + ticket.getId() + "/messages")
                        .with(authentication(asUser(OWNER_UID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateSupportMessageRequest("Toujours bloque", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorType").value("USER"));

        mockMvc.perform(get("/support/tickets/" + ticket.getId())
                        .with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_SUPPORT"));
    }

    @Test
    void markingATicketReadResetsItsUnreadCount() throws Exception {
        // Semer un ticket appartenant a owner avec un message ADMIN non lu
        SupportTicketEntity ticket = persistTicket(owner, SupportTicketStatus.WAITING_USER);
        SupportMessageEntity adminMsg = new SupportMessageEntity();
        adminMsg.setTicketId(ticket.getId());
        adminMsg.setAuthorType(SupportMessageAuthorType.ADMIN);
        adminMsg.setAuthorId(UUID.randomUUID());
        adminMsg.setContent("Bonjour, nous avons bien recu votre demande.");
        messageRepository.save(adminMsg);

        // Avant lecture : unreadCount = 1
        mockMvc.perform(get("/support/tickets").with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].unreadCount").value(1));

        // Marquer comme lu
        mockMvc.perform(post("/support/tickets/" + ticket.getId() + "/read")
                        .with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isNoContent());

        // Apres lecture : total unread = 0
        mockMvc.perform(get("/support/unread-count").with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void markingSomeoneElsesTicketReturns404() throws Exception {
        SupportTicketEntity ticket = persistTicket(owner, SupportTicketStatus.WAITING_USER);

        // intruder tente de marquer le ticket de owner comme lu
        mockMvc.perform(post("/support/tickets/" + ticket.getId() + "/read")
                        .with(authentication(asUser(INTRUDER_UID))))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------- apercu et resume

    @Test
    void listTickets_exposesThePreviewOfTheLastMessage() throws Exception {
        SupportTicketEntity ticket = persistTicket(owner, SupportTicketStatus.WAITING_USER);
        persistMessage(ticket, SupportMessageAuthorType.USER, "Premier message", 0);
        persistMessage(ticket, SupportMessageAuthorType.ADMIN,
                "Bonjour,\n\nnous avons   bien recu votre demande et nous revenons vers vous tres vite avec une reponse.", 1);

        mockMvc.perform(get("/support/tickets").with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].lastMessagePreview").value(
                        "Bonjour, nous avons bien recu votre demande et nous revenons vers vous tres…"))
                .andExpect(jsonPath("$.content[0].lastMessageFromAdmin").value(true))
                .andExpect(jsonPath("$.content[0].unreadCount").value(1));
    }

    @Test
    void listTickets_previewOfAnAttachmentOnlyMessage_isLocalized() throws Exception {
        SupportTicketEntity ticket = persistTicket(owner, SupportTicketStatus.WAITING_SUPPORT);
        persistMessage(ticket, SupportMessageAuthorType.USER, "", 0);

        mockMvc.perform(get("/support/tickets").with(authentication(asUser(OWNER_UID))))
                .andExpect(jsonPath("$.content[0].lastMessagePreview").value("Pièce jointe"))
                .andExpect(jsonPath("$.content[0].lastMessageFromAdmin").value(false));

        mockMvc.perform(get("/support/tickets")
                        .with(authentication(asUser(OWNER_UID)))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.content[0].lastMessagePreview").value("Attachment"));
    }

    @Test
    void summary_withoutTickets_hasANullLatestTicket() throws Exception {
        mockMvc.perform(get("/support/summary").with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.openTicketCount").value(0))
                // null explicite (et non champ absent) : l'API omet d'ordinaire les nulls.
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"latestTicket\":null")));
    }

    /** Le ticket ouvert le plus recent passe devant un ticket resolu plus recent. */
    @Test
    void summary_prefersTheMostRecentOpenTicket() throws Exception {
        SupportTicketEntity open = persistTicket(owner, SupportTicketStatus.WAITING_USER);
        open.setLastMessageAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(2));
        ticketRepository.save(open);
        persistMessage(open, SupportMessageAuthorType.ADMIN, "Il manque une photo.", 0);
        SupportTicketEntity resolved = persistTicket(owner, SupportTicketStatus.RESOLVED);
        persistMessage(resolved, SupportMessageAuthorType.USER, "Merci", 0);
        persistTicket(intruder, SupportTicketStatus.NEW);

        mockMvc.perform(get("/support/summary").with(authentication(asUser(OWNER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.openTicketCount").value(1))
                .andExpect(jsonPath("$.latestTicket.id").value(open.getId().toString()))
                .andExpect(jsonPath("$.latestTicket.subject").value("Paiement bloque"))
                .andExpect(jsonPath("$.latestTicket.lastMessagePreview").value("Il manque une photo."))
                .andExpect(jsonPath("$.latestTicket.lastMessageFromAdmin").value(true))
                .andExpect(jsonPath("$.latestTicket.lastMessageAt").exists())
                .andExpect(jsonPath("$.latestTicket.unreadCount").value(1));
    }

    @Test
    void summary_fallsBackToTheMostRecentTicketWhenAllAreResolved() throws Exception {
        SupportTicketEntity older = persistTicket(owner, SupportTicketStatus.RESOLVED);
        older.setLastMessageAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(3));
        ticketRepository.save(older);
        SupportTicketEntity newer = persistTicket(owner, SupportTicketStatus.RESOLVED);

        mockMvc.perform(get("/support/summary").with(authentication(asUser(OWNER_UID))))
                .andExpect(jsonPath("$.openTicketCount").value(0))
                .andExpect(jsonPath("$.latestTicket.id").value(newer.getId().toString()))
                .andExpect(jsonPath("$.latestTicket.lastMessageFromAdmin").value(false));
    }

    // ---------------------------------------------------------------- helpers

    private void persistMessage(SupportTicketEntity ticket, SupportMessageAuthorType author,
                                String content, int order) throws InterruptedException {
        SupportMessageEntity message = new SupportMessageEntity();
        message.setTicketId(ticket.getId());
        message.setAuthorType(author);
        message.setAuthorId(author == SupportMessageAuthorType.USER ? ticket.getUserId() : UUID.randomUUID());
        message.setContent(content);
        messageRepository.save(message);
        // created_at est pose a l'insertion : on espace les messages pour un ordre stable.
        Thread.sleep(15);
    }

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
        reply.setQuestionEn("When will my refund arrive?");
        reply.setAnswerEn("Within 5 to 10 business days.");
        reply.setSortOrder(sortOrder);
        reply.setActive(active);
        return reply;
    }
}
