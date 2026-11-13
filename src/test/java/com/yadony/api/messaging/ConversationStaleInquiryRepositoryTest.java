package com.yadony.api.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code findStaleInquiries} sur H2 (profil test) : seuil, COALESCE, type et suppression logique. */
@SpringBootTest
@ActiveProfiles("test")
class ConversationStaleInquiryRepositoryTest {

    @Autowired ConversationRepository repo;
    @Autowired JdbcTemplate jdbc;

    private ConversationEntity inquiry(LocalDateTime lastActivity, LocalDateTime createdAt) {
        ConversationEntity c = repo.saveAndFlush(ConversationEntity.forInquiry(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "conv_stale_" + UUID.randomUUID()));
        jdbc.update("UPDATE conversations SET last_activity_at = ?, created_at = ? WHERE id = ?",
                lastActivity, createdAt, c.getId());
        return c;
    }

    private boolean selected(ConversationEntity c, LocalDateTime cutoff) {
        return repo.findStaleInquiries(cutoff).stream().anyMatch(x -> x.getId().equals(c.getId()));
    }

    @Test
    void usesLastActivity_notCreation_andRespectsTheCutoff() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = now.minusDays(InquiryPolicy.INACTIVITY_DAYS);
        ConversationEntity old = inquiry(now.minusDays(31), now.minusDays(60));
        ConversationEntity recent = inquiry(now.minusDays(29), now.minusDays(60));
        ConversationEntity oldCreationRecentActivity = inquiry(now.minusDays(1), now.minusDays(90));

        assertThat(selected(old, cutoff)).isTrue();
        assertThat(selected(recent, cutoff)).isFalse();
        assertThat(selected(oldCreationRecentActivity, cutoff)).isFalse();
    }

    @Test
    void fallsBackOnCreation_whenLastActivityIsNull() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = now.minusDays(InquiryPolicy.INACTIVITY_DAYS);
        ConversationEntity oldCreation = inquiry(null, now.minusDays(40));
        ConversationEntity recentCreation = inquiry(null, now.minusDays(5));

        assertThat(selected(oldCreation, cutoff)).isTrue();
        assertThat(selected(recentCreation, cutoff)).isFalse();
    }

    @Test
    void neverSelectsAPromotedThread_evenWhenOld() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ConversationEntity promoted = inquiry(now.minusDays(100), now.minusDays(100));
        jdbc.update("UPDATE conversations SET kind = 'SENDER_TRAVELER' WHERE id = ?", promoted.getId());

        assertThat(selected(promoted, now.minusDays(InquiryPolicy.INACTIVITY_DAYS))).isFalse();
    }

    @Test
    void skipsAlreadyDeletedConversations() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ConversationEntity gone = inquiry(now.minusDays(40), now.minusDays(40));
        jdbc.update("UPDATE conversations SET deleted_at = ? WHERE id = ?", now, gone.getId());

        assertThat(selected(gone, now.minusDays(InquiryPolicy.INACTIVITY_DAYS))).isFalse();
    }
}
