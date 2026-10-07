package com.yadony.api.messaging;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /conversations/{id}/mute|unmute} de bout en bout (FLUTTER-CM) : 204, persistance
 * par participant, champ {@code notificationsMuted} propre à l'appelant, idempotence de l'audit,
 * 403 pour un tiers ou une conversation inconnue (même règle que l'archivage).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConversationMuteIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UserEntity sender;
    private UserEntity traveler;
    private ConversationEntity conversation;

    @BeforeEach
    void seed() {
        sender = persistUser("Awa");
        traveler = persistUser("Moussa");
        UUID bidId = UUID.randomUUID();
        conversation = conversationRepository.saveAndFlush(
                new ConversationEntity(bidId, sender.getId(), traveler.getId(), "conv_mute_" + bidId));
    }

    @Test
    void mute_isPerParticipant_andInvisibleToTheOther() throws Exception {
        mockMvc.perform(post("/conversations/{id}/mute", conversation.getId()).with(authentication(as(sender))))
                .andExpect(status().isNoContent());

        ConversationEntity saved = conversationRepository.findById(conversation.getId()).orElseThrow();
        assertThat(saved.isNotificationsMutedBy(sender.getId())).isTrue();
        assertThat(saved.isNotificationsMutedBy(traveler.getId())).isFalse();

        mockMvc.perform(get("/conversations/{id}", conversation.getId()).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationsMuted").value(true));
        mockMvc.perform(get("/conversations/{id}", conversation.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationsMuted").value(false));
    }

    @Test
    void mute_thenUnmute_isIdempotent_andAuditedOncePerChange() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/conversations/{id}/mute", conversation.getId()).with(authentication(as(traveler))))
                    .andExpect(status().isNoContent());
        }
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/conversations/{id}/unmute", conversation.getId()).with(authentication(as(traveler))))
                    .andExpect(status().isNoContent());
        }

        assertThat(conversationRepository.findById(conversation.getId()).orElseThrow()
                .isNotificationsMutedBy(traveler.getId())).isFalse();
        assertThat(auditActions()).containsOnlyOnce(
                "CONVERSATION_NOTIFICATIONS_MUTED", "CONVERSATION_NOTIFICATIONS_UNMUTED");
        mockMvc.perform(get("/conversations/{id}", conversation.getId()).with(authentication(as(traveler))))
                .andExpect(jsonPath("$.notificationsMuted").value(false));
    }

    @Test
    void thirdParty_andUnknownConversation_get403() throws Exception {
        UserEntity stranger = persistUser("Tiers");
        mockMvc.perform(post("/conversations/{id}/mute", conversation.getId()).with(authentication(as(stranger))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/conversations/{id}/unmute", conversation.getId()).with(authentication(as(stranger))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/conversations/{id}/mute", UUID.randomUUID()).with(authentication(as(sender))))
                .andExpect(status().isForbidden());

        ConversationEntity saved = conversationRepository.findById(conversation.getId()).orElseThrow();
        assertThat(saved.getSenderNotificationsMutedAt()).isNull();
        assertThat(saved.getTravelerNotificationsMutedAt()).isNull();
    }

    @Test
    void archivedConversation_canStillBeMuted() throws Exception {
        mockMvc.perform(post("/conversations/{id}/archive", conversation.getId()).with(authentication(as(sender))))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/conversations/{id}/mute", conversation.getId()).with(authentication(as(sender))))
                .andExpect(status().isNoContent());

        assertThat(conversationRepository.findById(conversation.getId()).orElseThrow()
                .isNotificationsMutedBy(sender.getId())).isTrue();
    }

    private List<String> auditActions() {
        // Lecture des seules actions : le payload jsonb ne se relit pas sous H2.
        return jdbcTemplate.queryForList(
                "SELECT action FROM audit_log WHERE entity_id = ?", String.class, conversation.getId());
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-mute-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }
}
