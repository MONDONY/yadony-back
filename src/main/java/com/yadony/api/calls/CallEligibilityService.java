package com.yadony.api.calls;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.CallAvailability;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.ContactWindow;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Règle unique : qui peut appeler qui, et quand (de l'acceptation jusqu'à J+3 après livraison, et
 * pendant le retour d'un colis annulé après sa remise).
 */
@Service
public class CallEligibilityService implements CallAvailability {

    public enum Reason { CALLS_DISABLED, NOT_FOUND, NOT_PARTICIPANT, CONVERSATION_CLOSED, OUT_OF_WINDOW, BLOCKED, CALLEE_UNAVAILABLE }

    public record Eligibility(Reason reason, ConversationEntity conversation, UUID calleeId) {
        public boolean allowed() { return reason == null; }
        static Eligibility denied(Reason r) { return new Eligibility(r, null, null); }
    }

    private final ConversationRepository conversations;
    private final BidRepository bids;
    private final UserRepository users;
    private final BlockVisibility blocks;
    private final StreamProperties properties;
    private final Clock clock;

    @Autowired
    public CallEligibilityService(ConversationRepository conversations, BidRepository bids, UserRepository users,
                                  BlockVisibility blocks, StreamProperties properties) {
        this(conversations, bids, users, blocks, properties, Clock.systemUTC());
    }

    CallEligibilityService(ConversationRepository conversations, BidRepository bids, UserRepository users,
                           BlockVisibility blocks, StreamProperties properties, Clock clock) {
        this.conversations = conversations;
        this.bids = bids;
        this.users = users;
        this.blocks = blocks;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canCall(UUID callerId, UUID conversationId) {
        return check(callerId, conversationId).allowed();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canCall(UUID callerId, UUID conversationId, boolean visibilityChecked) {
        return check(callerId, conversationId, visibilityChecked).allowed();
    }

    @Transactional(readOnly = true)
    public Eligibility check(UUID callerId, UUID conversationId) {
        return check(callerId, conversationId, false);
    }

    /**
     * @param visibilityChecked l'appelant a déjà écarté les contreparties masquées (liste des
     *                          conversations filtrée par {@code hiddenUserIdsFor}) : la règle
     *                          de blocage, symétrique, y est forcément négative.
     */
    private Eligibility check(UUID callerId, UUID conversationId, boolean visibilityChecked) {
        if (!properties.configured()) return Eligibility.denied(Reason.CALLS_DISABLED);

        ConversationEntity conv = conversations.findById(conversationId).orElse(null);
        if (conv == null || conv.getDeletedAt() != null) return Eligibility.denied(Reason.NOT_FOUND);

        UUID calleeId;
        if (callerId.equals(conv.getSenderId())) calleeId = conv.getTravelerId();
        else if (callerId.equals(conv.getTravelerId())) calleeId = conv.getSenderId();
        else return Eligibility.denied(Reason.NOT_PARTICIPANT);

        if (conv.isClosed() || conv.isDeletedByUser(callerId) || conv.isReadOnlyFor(callerId)) {
            return Eligibility.denied(Reason.CONVERSATION_CLOSED);
        }

        BidEntity bid = bids.findById(conv.getBidId()).orElse(null);
        if (bid == null || !inWindow(bid, conv)) return Eligibility.denied(Reason.OUT_OF_WINDOW);

        if (!visibilityChecked
                && (blocks.isHidden(callerId, calleeId) || blocks.isHidden(calleeId, callerId))) {
            return Eligibility.denied(Reason.BLOCKED);
        }

        // findById exclut déjà les comptes supprimés (@Where sur UserEntity).
        boolean calleeActive = users.findById(calleeId)
                .map(u -> u.getStatus() == UserStatus.ACTIVE).orElse(false);
        if (!calleeActive) return Eligibility.denied(Reason.CALLEE_UNAVAILABLE);

        return new Eligibility(null, conv, calleeId);
    }

    /** Règle partagée avec le bouton téléphone de la fiche colis ({@link ContactWindow}). */
    private boolean inWindow(BidEntity bid, ConversationEntity conv) {
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        // Retour d'un colis annulé après remise (FLUTTER-FM) : expéditeur et voyageur seulement.
        if (conv.isRecipientConversation()) {
            return ContactWindow.isOpen(bid.getStatus(), bid.getDeliveredAt(), properties.deliveryGraceDays(), now);
        }
        return ContactWindow.isOpen(bid, properties.deliveryGraceDays(), now);
    }
}
