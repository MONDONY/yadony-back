package com.yadony.api.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Modèle INQUIRY (V383) côté JPA : {@code findByAskerAndAnnouncement}, participants, promotion et
 * re-verrouillage par UPDATE gardés. Les contraintes SQL (unicité, CHECK) sont vérifiées sur un vrai PostgreSQL
 * dans {@link ConversationInquirySchemaIT}, le profil test tournant sur H2 sans Flyway.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConversationInquiryRepositoryTest {

    @Autowired ConversationRepository repo;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private ConversationEntity newInquiry(UUID asker, UUID announcement, UUID owner) {
        return repo.saveAndFlush(ConversationEntity.forInquiry(asker, announcement, owner,
                "conv_inq_" + UUID.randomUUID()));
    }

    @Test
    void findByAskerAndAnnouncement_returnsTheInquiryOfTheAskerAnnouncementPair() {
        UUID asker = UUID.randomUUID(), announcement = UUID.randomUUID(), owner = UUID.randomUUID();
        ConversationEntity saved = newInquiry(asker, announcement, owner);
        newInquiry(UUID.randomUUID(), announcement, owner); // autre demandeur, même annonce

        ConversationEntity found = repo.findByAskerAndAnnouncement(asker, announcement).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.isInquiry()).isTrue();
        assertThat(found.getKind()).isEqualTo(ConversationKind.INQUIRY);
        assertThat(found.getBidId()).isNull();
        assertThat(found.getSenderId()).isEqualTo(asker);
        assertThat(found.participantAId()).isEqualTo(asker);
        assertThat(found.getAskerId()).isEqualTo(asker);
        assertThat(found.getTravelerId()).isEqualTo(owner);
        assertThat(found.getAnnouncementId()).isEqualTo(announcement);
        assertThat(found.isQuotaLifted()).isFalse();
        assertThat(repo.findByAskerAndAnnouncement(UUID.randomUUID(), announcement)).isEmpty();
    }

    @Test
    void findByAskerAndAnnouncement_ignoresClosedAndDeletedThreads() {
        UUID asker = UUID.randomUUID(), announcement = UUID.randomUUID();
        ConversationEntity closed = newInquiry(asker, announcement, UUID.randomUUID());
        closed.close(LocalDateTime.now(ZoneOffset.UTC));
        repo.saveAndFlush(closed);
        assertThat(repo.findByAskerAndAnnouncement(asker, announcement)).isEmpty();

        UUID asker2 = UUID.randomUUID();
        ConversationEntity deleted = newInquiry(asker2, announcement, UUID.randomUUID());
        deleted.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        repo.saveAndFlush(deleted);
        assertThat(repo.findByAskerAndAnnouncement(asker2, announcement)).isEmpty();
    }

    @Test
    void senderTravelerWithBid_keepsItsBehaviour() {
        UUID bid = UUID.randomUUID();
        ConversationEntity c = repo.saveAndFlush(new ConversationEntity(bid, UUID.randomUUID(),
                UUID.randomUUID(), "conv_bid_" + bid));

        assertThat(c.isInquiry()).isFalse();
        assertThat(c.getKind()).isEqualTo(ConversationKind.SENDER_TRAVELER);
        assertThat(repo.findByBidId(bid)).get().extracting(ConversationEntity::getId).isEqualTo(c.getId());
        assertThat(repo.findByAskerAndAnnouncement(c.getSenderId(), UUID.randomUUID())).isEmpty();
    }

    // ── Promotion et reverrouillage : UPDATE gardés (aucun mutateur d'entité) ──

    @Test
    void guardedPromotion_thenRelock_roundTrips() {
        UUID asker = UUID.randomUUID(), announcement = UUID.randomUUID(), bid = UUID.randomUUID();
        ConversationEntity c = newInquiry(asker, announcement, UUID.randomUUID());
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC);

        assertThat(repo.promoteInquiryGuarded(c.getId(), bid, at)).isEqualTo(1);
        ConversationEntity promoted = repo.findByBidId(bid).orElseThrow();
        assertThat(promoted.getId()).isEqualTo(c.getId());
        assertThat(promoted.getKind()).isEqualTo(ConversationKind.SENDER_TRAVELER);
        assertThat(promoted.isQuotaLifted()).isTrue();
        assertThat(promoted.getAskerId()).isEqualTo(asker);
        assertThat(promoted.getAnnouncementId()).isEqualTo(announcement);

        assertThat(repo.relockInquiryGuarded(c.getId(), at.plusDays(1))).isEqualTo(1);
        ConversationEntity relocked = repo.findByAskerAndAnnouncement(asker, announcement).orElseThrow();
        assertThat(relocked.getBidId()).isNull();
        assertThat(relocked.isQuotaLifted()).isFalse();
        assertThat(relocked.getKind()).isEqualTo(ConversationKind.INQUIRY);
        assertThat(repo.findByBidId(bid)).isEmpty();
    }

    @Test
    void guardedRelock_ofAThreadBornAtAcceptance_isRefused() {
        ConversationEntity c = repo.saveAndFlush(new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "conv_plain_" + UUID.randomUUID()));

        assertThat(repo.relockInquiryGuarded(c.getId(), LocalDateTime.now(ZoneOffset.UTC))).isZero();
        assertThat(repo.findById(c.getId()).orElseThrow().getKind()).isEqualTo(ConversationKind.SENDER_TRAVELER);
    }

    @Test
    void findByAskerAndAnnouncement_returnsPromotedThreadToo_butNotClosedOrDeleted() {
        UUID asker = UUID.randomUUID(), announcement = UUID.randomUUID();
        ConversationEntity c = newInquiry(asker, announcement, UUID.randomUUID());
        assertThat(repo.findByAskerAndAnnouncement(asker, announcement)).get()
                .extracting(ConversationEntity::getId).isEqualTo(c.getId());

        assertThat(repo.promoteInquiryGuarded(c.getId(), UUID.randomUUID(), LocalDateTime.now(ZoneOffset.UTC)))
                .isEqualTo(1);
        ConversationEntity promoted = repo.findByAskerAndAnnouncement(asker, announcement).orElseThrow();
        assertThat(promoted.getId()).isEqualTo(c.getId());
        assertThat(promoted.getKind()).isEqualTo(ConversationKind.SENDER_TRAVELER);

        promoted.close(LocalDateTime.now(ZoneOffset.UTC));
        repo.saveAndFlush(promoted);
        assertThat(repo.findByAskerAndAnnouncement(asker, announcement)).isEmpty();
        assertThat(repo.findByAskerAndAnnouncement(UUID.randomUUID(), announcement)).isEmpty();
    }

    @Test
    void guardedPromotion_stateGuards() {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC);
        ConversationEntity ok = newInquiry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(repo.promoteInquiryGuarded(ok.getId(), UUID.randomUUID(), at)).isEqualTo(1);
        // Même paramètre :at pour la dernière activité et la levée du quota (comparé en SQL : aucun fuseau).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversations WHERE id = ? "
                + "AND last_activity_at = quota_lifted_at", Integer.class, ok.getId())).isEqualTo(1);
        assertThat(repo.findById(ok.getId()).orElseThrow().getKind()).isEqualTo(ConversationKind.SENDER_TRAVELER);

        // déjà promue
        assertThat(repo.promoteInquiryGuarded(ok.getId(), UUID.randomUUID(), at)).isZero();
        // fermée
        ConversationEntity closed = newInquiry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        closed.close(at);
        repo.saveAndFlush(closed);
        assertThat(repo.promoteInquiryGuarded(closed.getId(), UUID.randomUUID(), at)).isZero();
        // autre type
        ConversationEntity plain = repo.saveAndFlush(new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "conv_plain_" + UUID.randomUUID()));
        assertThat(repo.promoteInquiryGuarded(plain.getId(), UUID.randomUUID(), at)).isZero();
        assertThat(repo.findById(closed.getId()).orElseThrow().getKind()).isEqualTo(ConversationKind.INQUIRY);
    }

    @Test
    void guardedRelock_stateGuards() {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC);
        // INQUIRY non promue : refus
        ConversationEntity inquiry = newInquiry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(repo.relockInquiryGuarded(inquiry.getId(), at)).isZero();
        // promue puis fermée : refus
        ConversationEntity closed = newInquiry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(repo.promoteInquiryGuarded(closed.getId(), UUID.randomUUID(), at)).isEqualTo(1);
        ConversationEntity reloaded = repo.findById(closed.getId()).orElseThrow();
        reloaded.close(at);
        repo.saveAndFlush(reloaded);
        assertThat(repo.relockInquiryGuarded(closed.getId(), at)).isZero();
        // promue : ok, plus de bid, dernière activité avancée
        ConversationEntity promoted = newInquiry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(repo.promoteInquiryGuarded(promoted.getId(), UUID.randomUUID(), at)).isEqualTo(1);
        LocalDateTime activityAtPromotion = lastActivityAt(promoted);
        assertThat(repo.relockInquiryGuarded(promoted.getId(), at.plusDays(1))).isEqualTo(1);
        ConversationEntity relocked = repo.findById(promoted.getId()).orElseThrow();
        assertThat(relocked.isInquiry()).isTrue();
        assertThat(relocked.getBidId()).isNull();
        assertThat(lastActivityAt(promoted)).isAfter(activityAtPromotion);
    }

    private LocalDateTime lastActivityAt(ConversationEntity c) {
        return jdbc.queryForObject("SELECT last_activity_at FROM conversations WHERE id = ?",
                LocalDateTime.class, c.getId());
    }

    @Test
    void policyConstants() {
        assertThat(InquiryPolicy.MESSAGE_LIMIT).isEqualTo(10);
        assertThat(InquiryPolicy.INACTIVITY_DAYS).isEqualTo(30);
    }

    // ── Liste du créateur : une INQUIRY sans premier message du demandeur reste cachée ──

    @Test
    void creatorLists_hideAnInquiryUntilTheFirstAskerMessage() {
        UUID asker = UUID.randomUUID(), owner = UUID.randomUUID(), announcement = UUID.randomUUID();
        ConversationEntity c = newInquiry(asker, announcement, owner);
        UUID hidden = UUID.randomUUID();
        org.springframework.data.domain.Pageable all = org.springframework.data.domain.Pageable.unpaged();

        assertThat(repo.findByParticipant(owner, all).getContent()).doesNotContain(c);
        assertThat(repo.findByParticipantExcludingHidden(owner, java.util.Set.of(hidden), all).getContent())
                .extracting(ConversationEntity::getId).doesNotContain(c.getId());
        assertThat(repo.countActiveByParticipant(owner)).isZero();
        // Le demandeur, lui, voit toujours son fil.
        assertThat(repo.findByParticipant(asker, all).getContent())
                .extracting(ConversationEntity::getId).contains(c.getId());
        assertThat(repo.countActiveByParticipant(asker)).isEqualTo(1);
        assertThat(repo.findByParticipantExcludingHidden(asker, java.util.Set.of(hidden), all).getContent())
                .extracting(ConversationEntity::getId).contains(c.getId());

        repo.markInquiryFirstMessage(c.getId(), LocalDateTime.now(ZoneOffset.UTC));

        assertThat(repo.findByParticipant(owner, all).getContent())
                .extracting(ConversationEntity::getId).contains(c.getId());
        assertThat(repo.findByParticipantExcludingHidden(owner, java.util.Set.of(hidden), all).getContent())
                .extracting(ConversationEntity::getId).contains(c.getId());
        assertThat(repo.countActiveByParticipant(owner)).isEqualTo(1);
    }

    // ── Ligne supprimée (purge INQUIRY, deleted_at) : invisible de toutes les requêtes participant ──

    @Test
    void deletedRow_isInvisibleToEveryParticipantQuery() {
        UUID asker = UUID.randomUUID(), owner = UUID.randomUUID();
        ConversationEntity c = newInquiry(asker, UUID.randomUUID(), owner);
        repo.markInquiryFirstMessage(c.getId(), LocalDateTime.now(ZoneOffset.UTC));
        ConversationEntity archived = newInquiry(asker, UUID.randomUUID(), owner);
        archived.archiveForUser(asker);
        repo.saveAndFlush(archived);
        org.springframework.data.domain.Pageable all = org.springframework.data.domain.Pageable.unpaged();
        java.util.Set<UUID> hidden = java.util.Set.of(UUID.randomUUID());

        // Témoin : avant la suppression, chaque requête voit le fil.
        assertThat(repo.findByParticipant(asker, all).getContent()).extracting(ConversationEntity::getId).contains(c.getId());
        assertThat(repo.findByParticipant(owner, all).getContent()).extracting(ConversationEntity::getId).contains(c.getId());
        assertThat(repo.findByParticipantExcludingHidden(asker, hidden, all).getContent())
                .extracting(ConversationEntity::getId).contains(c.getId());
        assertThat(repo.countActiveByParticipant(asker)).isEqualTo(1);
        assertThat(repo.countActiveByParticipant(owner)).isEqualTo(1);
        assertThat(repo.findByIdAndParticipant(c.getId(), asker)).isPresent();
        assertThat(repo.findByIdAndParticipantIgnoreArchived(c.getId(), asker)).isPresent();
        assertThat(repo.findByIdAndParticipantIgnoreDeleted(c.getId(), asker)).isPresent();
        assertThat(repo.findArchivedByParticipant(asker, all).getContent())
                .extracting(ConversationEntity::getId).contains(archived.getId());

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("UPDATE conversations SET deleted_at = ? WHERE id IN (?, ?)", now, c.getId(), archived.getId());

        assertThat(repo.findByParticipant(asker, all).getContent()).extracting(ConversationEntity::getId)
                .doesNotContain(c.getId(), archived.getId());
        assertThat(repo.findByParticipant(owner, all).getContent()).extracting(ConversationEntity::getId)
                .doesNotContain(c.getId());
        assertThat(repo.findByParticipantExcludingHidden(asker, hidden, all).getContent())
                .extracting(ConversationEntity::getId).doesNotContain(c.getId());
        assertThat(repo.findByParticipantExcludingHidden(owner, hidden, all).getContent())
                .extracting(ConversationEntity::getId).doesNotContain(c.getId());
        assertThat(repo.countActiveByParticipant(asker)).isZero();
        assertThat(repo.countActiveByParticipant(owner)).isZero();
        for (UUID user : new UUID[] {asker, owner}) {
            assertThat(repo.findByIdAndParticipant(c.getId(), user)).isEmpty();
            assertThat(repo.findByIdAndParticipantIgnoreArchived(c.getId(), user)).isEmpty();
            assertThat(repo.findByIdAndParticipantIgnoreDeleted(c.getId(), user)).isEmpty();
        }
        assertThat(repo.findArchivedByParticipant(asker, all).getContent())
                .extracting(ConversationEntity::getId).doesNotContain(archived.getId());
    }

    @Test
    void creatorArchivedList_hidesAnInquiryWithoutFirstMessage() {
        UUID asker = UUID.randomUUID(), owner = UUID.randomUUID();
        ConversationEntity c = newInquiry(asker, UUID.randomUUID(), owner);
        c.archiveForUser(owner);
        repo.saveAndFlush(c);
        org.springframework.data.domain.Pageable all = org.springframework.data.domain.Pageable.unpaged();

        assertThat(repo.findArchivedByParticipant(owner, all).getContent())
                .extracting(ConversationEntity::getId).doesNotContain(c.getId());

        repo.markInquiryFirstMessage(c.getId(), LocalDateTime.now(ZoneOffset.UTC));

        assertThat(repo.findArchivedByParticipant(owner, all).getContent())
                .extracting(ConversationEntity::getId).contains(c.getId());
    }
}
