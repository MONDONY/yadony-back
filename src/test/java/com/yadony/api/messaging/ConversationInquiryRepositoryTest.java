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
    void policyConstants() {
        assertThat(InquiryPolicy.MESSAGE_LIMIT).isEqualTo(10);
        assertThat(InquiryPolicy.INACTIVITY_DAYS).isEqualTo(30);
    }
}
