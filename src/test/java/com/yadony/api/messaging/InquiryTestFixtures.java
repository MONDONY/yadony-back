package com.yadony.api.messaging;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * État en mémoire d'une conversation de questions après les UPDATE gardés du dépôt
 * ({@link ConversationRepository#promoteInquiryGuarded}, {@link ConversationRepository#relockInquiryGuarded}),
 * pour les tests unitaires où le dépôt est simulé. L'entité n'expose aucun mutateur pour ces champs :
 * en production, seuls ces UPDATE font basculer une ligne.
 */
final class InquiryTestFixtures {

    private InquiryTestFixtures() {}

    /** Effet de {@code promoteInquiryGuarded} : bid rattaché, type SENDER_TRAVELER, quota levé. */
    static ConversationEntity promoted(ConversationEntity conv, UUID bidId, LocalDateTime at) {
        ReflectionTestUtils.setField(conv, "bidId", bidId);
        ReflectionTestUtils.setField(conv, "kind", ConversationKind.SENDER_TRAVELER);
        ReflectionTestUtils.setField(conv, "quotaLiftedAt", at);
        ReflectionTestUtils.setField(conv, "lastActivityAt", at);
        return conv;
    }

    /** Effet de {@code relockInquiryGuarded} : plus de bid, retour en INQUIRY, quota réappliqué. */
    static ConversationEntity relocked(ConversationEntity conv, LocalDateTime at) {
        ReflectionTestUtils.setField(conv, "bidId", null);
        ReflectionTestUtils.setField(conv, "kind", ConversationKind.INQUIRY);
        ReflectionTestUtils.setField(conv, "quotaLiftedAt", null);
        ReflectionTestUtils.setField(conv, "lastActivityAt", at);
        return conv;
    }
}
