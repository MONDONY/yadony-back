package com.yadony.api.messaging;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FLUTTER-QQ : lecture seule dérivée du bid, conversation expéditeur ↔ voyageur seulement. */
class ConversationServiceSenderNoShowTest {

    private final UUID bidId = UUID.randomUUID();

    private static BidEntity cancelled(String reason) {
        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setStatus(BidStatus.CANCELLED);
        bid.setRejectionReason(reason);
        return bid;
    }

    @Test
    void conversationExpediteurVoyageur_lectureSeule() {
        ConversationEntity conv = new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "c");
        assertThat(ConversationService.isClosedBySenderNoShow(conv, cancelled(BidEntity.REJECTION_SENDER_NO_SHOW)))
                .isTrue();
    }

    @Test
    void conversationDestinataire_nonConcernee() {
        ConversationEntity conv = ConversationEntity.forRecipient(bidId, UUID.randomUUID(), UUID.randomUUID(), "c");
        assertThat(ConversationService.isClosedBySenderNoShow(conv, cancelled(BidEntity.REJECTION_SENDER_NO_SHOW)))
                .isFalse();
    }

    @Test
    void autreMotifOuSansBid_ouverte() {
        ConversationEntity conv = new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "c");
        assertThat(ConversationService.isClosedBySenderNoShow(conv, cancelled("CANCELLED_BY_SENDER"))).isFalse();
        assertThat(ConversationService.isClosedBySenderNoShow(conv, null)).isFalse();
    }
}
