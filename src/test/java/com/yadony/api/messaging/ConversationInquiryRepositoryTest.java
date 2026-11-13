package com.yadony.api.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Modèle INQUIRY (V383) côté JPA : {@code findInquiry}, participants, promotion et
 * re-verrouillage. Les contraintes SQL (unicité, CHECK) sont vérifiées sur un vrai PostgreSQL
 * dans {@link ConversationInquirySchemaIT}, le profil test tournant sur H2 sans Flyway.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConversationInquiryRepositoryTest {

    @Autowired ConversationRepository repo;

    private ConversationEntity newInquiry(UUID asker, UUID announcement, UUID owner) {
        return repo.saveAndFlush(ConversationEntity.forInquiry(asker, announcement, owner,
                "conv_inq_" + UUID.randomUUID()));
    }

    @Test
    void findInquiry_returnsTheConversationOfTheAskerAnnouncementPair() {
        UUID asker = UUID.randomUUID(), announcement = UUID.randomUUID(), owner = UUID.randomUUID();
        ConversationEntity saved = newInquiry(asker, announcement, owner);
        newInquiry(UUID.randomUUID(), announcement, owner); // autre demandeur, même annonce

        ConversationEntity found = repo.findInquiry(asker, announcement).orElseThrow();

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
        assertThat(repo.findInquiry(UUID.randomUUID(), announcement)).isEmpty();
    }

    @Test
    void findInquiry_ignoresClosedAndDeletedThreads() {
        UUID asker = UUID.randomUUID(), announcement = UUID.randomUUID();
        ConversationEntity closed = newInquiry(asker, announcement, UUID.randomUUID());
        closed.close(LocalDateTime.now(ZoneOffset.UTC));
        repo.saveAndFlush(closed);
        assertThat(repo.findInquiry(asker, announcement)).isEmpty();

        UUID asker2 = UUID.randomUUID();
        ConversationEntity deleted = newInquiry(asker2, announcement, UUID.randomUUID());
        deleted.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        repo.saveAndFlush(deleted);
        assertThat(repo.findInquiry(asker2, announcement)).isEmpty();
    }

    @Test
    void senderTravelerWithBid_keepsItsBehaviour() {
        UUID bid = UUID.randomUUID();
        ConversationEntity c = repo.saveAndFlush(new ConversationEntity(bid, UUID.randomUUID(),
                UUID.randomUUID(), "conv_bid_" + bid));

        assertThat(c.isInquiry()).isFalse();
        assertThat(c.getKind()).isEqualTo(ConversationKind.SENDER_TRAVELER);
        assertThat(repo.findByBidId(bid)).get().extracting(ConversationEntity::getId).isEqualTo(c.getId());
        assertThat(repo.findInquiry(c.getSenderId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void promoteToBid_thenRelock_roundTrips() {
        UUID asker = UUID.randomUUID(), announcement = UUID.randomUUID(), bid = UUID.randomUUID();
        ConversationEntity c = newInquiry(asker, announcement, UUID.randomUUID());
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC);

        c.promoteToBid(bid, at);
        repo.saveAndFlush(c);
        ConversationEntity promoted = repo.findByBidId(bid).orElseThrow();
        assertThat(promoted.getKind()).isEqualTo(ConversationKind.SENDER_TRAVELER);
        assertThat(promoted.isQuotaLifted()).isTrue();
        assertThat(repo.findInquiry(asker, announcement)).isEmpty();

        promoted.relockToInquiry(at.plusDays(1));
        repo.saveAndFlush(promoted);
        ConversationEntity relocked = repo.findInquiry(asker, announcement).orElseThrow();
        assertThat(relocked.getBidId()).isNull();
        assertThat(relocked.isQuotaLifted()).isFalse();
        assertThat(relocked.getKind()).isEqualTo(ConversationKind.INQUIRY);
    }

    @Test
    void relockToInquiry_withoutAnnouncementAndAsker_isRejected() {
        ConversationEntity c = new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "conv_plain");
        assertThatThrownBy(() -> c.relockToInquiry(LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void findByAskerAndAnnouncement_returnsPromotedThreadToo_butNotClosedOrDeleted() {
        UUID asker = UUID.randomUUID(), announcement = UUID.randomUUID();
        ConversationEntity c = newInquiry(asker, announcement, UUID.randomUUID());
        assertThat(repo.findByAskerAndAnnouncement(asker, announcement)).get()
                .extracting(ConversationEntity::getId).isEqualTo(c.getId());

        c.promoteToBid(UUID.randomUUID(), LocalDateTime.now(ZoneOffset.UTC));
        repo.saveAndFlush(c);
        assertThat(repo.findInquiry(asker, announcement)).isEmpty();
        assertThat(repo.findByAskerAndAnnouncement(asker, announcement)).get()
                .extracting(ConversationEntity::getId).isEqualTo(c.getId());

        c.close(LocalDateTime.now(ZoneOffset.UTC));
        repo.saveAndFlush(c);
        assertThat(repo.findByAskerAndAnnouncement(asker, announcement)).isEmpty();
        assertThat(repo.findByAskerAndAnnouncement(UUID.randomUUID(), announcement)).isEmpty();
    }

    @Test
    void promoteToBid_guards() {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC);
        ConversationEntity ok = ConversationEntity.forInquiry(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "c1");
        ok.promoteToBid(UUID.randomUUID(), at);
        assertThat(ok.getLastActivityAt()).isEqualTo(at);
        assertThat(ok.getKind()).isEqualTo(ConversationKind.SENDER_TRAVELER);

        // déjà promue
        assertThatThrownBy(() -> ok.promoteToBid(UUID.randomUUID(), at)).isInstanceOf(IllegalStateException.class);
        // bid nul
        ConversationEntity fresh = ConversationEntity.forInquiry(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "c2");
        assertThatThrownBy(() -> fresh.promoteToBid(null, at)).isInstanceOf(NullPointerException.class);
        // fermée
        ConversationEntity closed = ConversationEntity.forInquiry(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "c3");
        closed.close(at);
        assertThatThrownBy(() -> closed.promoteToBid(UUID.randomUUID(), at)).isInstanceOf(IllegalStateException.class);
        // autre type
        ConversationEntity plain = new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "c4");
        assertThatThrownBy(() -> plain.promoteToBid(UUID.randomUUID(), at)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void relockToInquiry_guards() {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC);
        // INQUIRY non promue : refus
        ConversationEntity inquiry = ConversationEntity.forInquiry(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "r1");
        assertThatThrownBy(() -> inquiry.relockToInquiry(at)).isInstanceOf(IllegalStateException.class);
        // promue puis fermée : refus
        ConversationEntity closed = ConversationEntity.forInquiry(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "r2");
        closed.promoteToBid(UUID.randomUUID(), at);
        closed.close(at);
        assertThatThrownBy(() -> closed.relockToInquiry(at)).isInstanceOf(IllegalStateException.class);
        // promue : ok
        ConversationEntity promoted = ConversationEntity.forInquiry(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "r3");
        promoted.promoteToBid(UUID.randomUUID(), at);
        promoted.relockToInquiry(at.plusDays(1));
        assertThat(promoted.isInquiry()).isTrue();
        assertThat(promoted.getBidId()).isNull();
        assertThat(promoted.getLastActivityAt()).isEqualTo(at.plusDays(1));
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
