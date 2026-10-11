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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /conversations/batch/archive|unarchive|delete} de bout en bout (FLUTTER-M4) :
 * résultat par id, propriété vérifiée pour chaque id (un fil d'autrui est refusé sans
 * bloquer les autres), suppression douce propre à l'appelant, audit par fil, et borne de
 * 100 identifiants.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConversationBatchIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UserEntity sender;
    private UserEntity traveler;
    private UserEntity stranger;
    private ConversationEntity first;
    private ConversationEntity second;
    private ConversationEntity foreign;

    @BeforeEach
    void seed() {
        sender = persistUser("Awa");
        traveler = persistUser("Moussa");
        stranger = persistUser("Tiers");
        first = persistConversation(sender, traveler);
        second = persistConversation(sender, traveler);
        foreign = persistConversation(stranger, traveler);
    }

    @Test
    void archive_thenUnarchive_batch_reportsPerId_andRefusesForeignConversation() throws Exception {
        mockMvc.perform(post("/conversations/batch/archive").with(authentication(as(sender)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(first.getId(), foreign.getId(), second.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.results[0].id").value(first.getId().toString()))
                .andExpect(jsonPath("$.results[0].success").value(true))
                .andExpect(jsonPath("$.results[1].success").value(false))
                .andExpect(jsonPath("$.results[1].status").value(403))
                .andExpect(jsonPath("$.results[2].success").value(true));

        assertThat(reload(first).isArchivedByUser(sender.getId())).isTrue();
        assertThat(reload(second).isArchivedByUser(sender.getId())).isTrue();
        assertThat(reload(first).isArchivedByUser(traveler.getId())).isFalse();
        assertThat(reload(foreign).isArchivedByUser(stranger.getId())).isFalse();
        assertThat(auditActions(first)).contains("CONVERSATION_ARCHIVED");

        mockMvc.perform(post("/conversations/batch/unarchive").with(authentication(as(sender)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(first.getId(), second.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(2));

        assertThat(reload(first).isArchivedByUser(sender.getId())).isFalse();
        assertThat(reload(second).isArchivedByUser(sender.getId())).isFalse();
    }

    @Test
    void delete_batch_isSoftAndPerUser() throws Exception {
        mockMvc.perform(post("/conversations/batch/delete").with(authentication(as(sender)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(first.getId(), second.getId(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.results[2].status").value(403));

        ConversationEntity deleted = reload(first);
        assertThat(deleted.isDeletedByUser(sender.getId())).isTrue();
        assertThat(deleted.isDeletedByUser(traveler.getId())).isFalse();
        assertThat(reload(second).isDeletedByUser(sender.getId())).isTrue();
        assertThat(auditActions(first)).contains("CONVERSATION_DELETED");
    }

    @Test
    void emptyOrOversizedBatch_isRejectedWith422() throws Exception {
        mockMvc.perform(post("/conversations/batch/archive").with(authentication(as(sender)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isUnprocessableEntity());

        UUID[] tooMany = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toArray(UUID[]::new);
        mockMvc.perform(post("/conversations/batch/delete").with(authentication(as(sender)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tooMany)))
                .andExpect(status().isUnprocessableEntity());
    }

    private ConversationEntity reload(ConversationEntity c) {
        return conversationRepository.findById(c.getId()).orElseThrow();
    }

    private List<String> auditActions(ConversationEntity c) {
        return jdbcTemplate.queryForList(
                "SELECT action FROM audit_log WHERE entity_id = ?", String.class, c.getId());
    }

    private static String body(UUID... ids) {
        return "{\"ids\":[" + java.util.Arrays.stream(ids)
                .map(id -> "\"" + id + "\"").collect(Collectors.joining(",")) + "]}";
    }

    private ConversationEntity persistConversation(UserEntity s, UserEntity t) {
        UUID bidId = UUID.randomUUID();
        return conversationRepository.saveAndFlush(
                new ConversationEntity(bidId, s.getId(), t.getId(), "conv_batch_" + bidId));
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-batch-" + UUID.randomUUID());
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
