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

    @Test
    void reserveStaleInquiry_softDeletesOnlyWhileStillInactiveAndInquiry_thenReleaseUndoes() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = now.minusDays(InquiryPolicy.INACTIVITY_DAYS);
        ConversationEntity stale = inquiry(now.minusDays(40), now.minusDays(40));
        ConversationEntity awakened = inquiry(now.minusDays(1), now.minusDays(40));
        ConversationEntity promoted = inquiry(now.minusDays(40), now.minusDays(40));
        jdbc.update("UPDATE conversations SET kind = 'SENDER_TRAVELER' WHERE id = ?", promoted.getId());

        assertThat(repo.reserveStaleInquiry(awakened.getId(), now, cutoff)).isZero();
        assertThat(repo.reserveStaleInquiry(promoted.getId(), now, cutoff)).isZero();
        assertThat(repo.reserveStaleInquiry(stale.getId(), now, cutoff)).isEqualTo(1);
        assertThat(repo.reserveStaleInquiry(stale.getId(), now, cutoff)).isZero();
        assertThat(deletedAt(stale)).isNotNull();
        assertThat(deletedAt(promoted)).isNull();

        assertThat(repo.releaseInquiryReservation(stale.getId(), now.plusSeconds(1))).isZero(); // pas SA réservation
        assertThat(deletedAt(stale)).isNotNull();
        assertThat(repo.releaseInquiryReservation(stale.getId(), now)).isEqualTo(1);
        assertThat(deletedAt(stale)).isNull();
        assertThat(repo.releaseInquiryReservation(promoted.getId(), now)).isZero();
    }

    @Test
    void guardedPromotion_neverPromotesAThreadDeletedByOneSide() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ConversationEntity deletedByAsker = inquiry(now, now);
        ConversationEntity deletedByOwner = inquiry(now, now);
        jdbc.update("UPDATE conversations SET sender_deleted_at = ? WHERE id = ?", now, deletedByAsker.getId());
        jdbc.update("UPDATE conversations SET traveler_deleted_at = ? WHERE id = ?", now, deletedByOwner.getId());

        assertThat(repo.promoteInquiryGuarded(deletedByAsker.getId(), UUID.randomUUID(), now)).isZero();
        assertThat(repo.promoteInquiryGuarded(deletedByOwner.getId(), UUID.randomUUID(), now)).isZero();
        for (ConversationEntity c : new ConversationEntity[] {deletedByAsker, deletedByOwner}) {
            assertThat(jdbc.queryForObject("SELECT kind FROM conversations WHERE id = ?", String.class, c.getId()))
                    .isEqualTo("INQUIRY");
            assertThat(jdbc.queryForObject("SELECT bid_id FROM conversations WHERE id = ?", Object.class, c.getId()))
                    .isNull();
        }
    }

    @Test
    void guardedPromotion_beforeAnyMessage_datesTheFirstMessage_soARelockedThreadStaysListedForTheCreator() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        ConversationEntity c = inquiry(now, now);
        UUID owner = c.getTravelerId();
        org.springframework.data.domain.Pageable all = org.springframework.data.domain.Pageable.unpaged();

        assertThat(firstMessageAt(c)).isNull();
        assertThat(repo.promoteInquiryGuarded(c.getId(), UUID.randomUUID(), now)).isEqualTo(1);
        // Même paramètre :at que quota_lifted_at, dans le même UPDATE (comparé en SQL : aucun fuseau).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversations WHERE id = ? "
                + "AND inquiry_first_message_at = quota_lifted_at", Integer.class, c.getId())).isEqualTo(1);
        assertThat(repo.relockInquiryGuarded(c.getId(), now.plusMinutes(5))).isEqualTo(1);

        // Le créateur voyait le fil promu : le reverrouillage ne doit pas le lui retirer.
        assertThat(repo.findByParticipant(owner, all).getContent())
                .extracting(ConversationEntity::getId).contains(c.getId());
    }

    @Test
    void guardedPromotion_keepsAnEarlierFirstMessageDate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        ConversationEntity c = inquiry(now, now);
        repo.markInquiryFirstMessage(c.getId(), now.minusDays(2));
        LocalDateTime before = firstMessageAt(c);
        assertThat(before).isNotNull();

        assertThat(repo.promoteInquiryGuarded(c.getId(), UUID.randomUUID(), now)).isEqualTo(1);

        assertThat(firstMessageAt(c)).isEqualTo(before);
    }

    private LocalDateTime firstMessageAt(ConversationEntity c) {
        return jdbc.queryForObject("SELECT inquiry_first_message_at FROM conversations WHERE id = ?",
                LocalDateTime.class, c.getId());
    }

    private Object deletedAt(ConversationEntity c) {
        return jdbc.queryForObject("SELECT deleted_at FROM conversations WHERE id = ?", Object.class, c.getId());
    }

    @Test
    void guardedPromotion_andRelock_neverTouchAReservedOrClosedRow() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID bid = UUID.randomUUID();
        ConversationEntity open = inquiry(now, now);
        ConversationEntity reserved = inquiry(now.minusDays(40), now.minusDays(40));
        ConversationEntity closed = inquiry(now, now);
        jdbc.update("UPDATE conversations SET closed_at = ? WHERE id = ?", now, closed.getId());
        assertThat(repo.reserveStaleInquiry(reserved.getId(), now, now.minusDays(30))).isEqualTo(1);

        assertThat(repo.promoteInquiryGuarded(reserved.getId(), bid, now)).isZero();
        assertThat(repo.promoteInquiryGuarded(closed.getId(), bid, now)).isZero();
        assertThat(deletedAt(reserved)).isNotNull();

        assertThat(repo.promoteInquiryGuarded(open.getId(), bid, now)).isEqualTo(1);
        assertThat(repo.promoteInquiryGuarded(open.getId(), bid, now)).isZero();
        assertThat(jdbc.queryForObject("SELECT kind FROM conversations WHERE id = ?", String.class, open.getId()))
                .isEqualTo("SENDER_TRAVELER");

        assertThat(repo.relockInquiryGuarded(open.getId(), now)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT kind FROM conversations WHERE id = ?", String.class, open.getId()))
                .isEqualTo("INQUIRY");
        assertThat(jdbc.queryForObject("SELECT bid_id FROM conversations WHERE id = ?", Object.class, open.getId()))
                .isNull();
        assertThat(repo.relockInquiryGuarded(open.getId(), now)).isZero();
    }
}
