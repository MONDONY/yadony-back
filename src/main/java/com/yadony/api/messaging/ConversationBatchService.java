package com.yadony.api.messaging;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.messaging.dto.BatchConversationResponse;
import com.yadony.api.messaging.dto.BatchConversationResponse.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Actions groupées de la liste des conversations (sélection multiple, FLUTTER-M4).
 *
 * <p>Chaque identifiant passe par la méthode unitaire de {@link ConversationService}, qui
 * vérifie la propriété (participant non supprimé, sinon 403), applique la suppression
 * douce et journalise dans {@code audit_log} exactement comme l'appel unitaire. Cette
 * classe n'est volontairement pas transactionnelle : chaque fil a sa propre transaction,
 * un refus sur l'un n'annule pas les autres et le résultat est rendu par identifiant.
 */
@Service
public class ConversationBatchService {

    private static final Logger log = LoggerFactory.getLogger(ConversationBatchService.class);

    @FunctionalInterface
    interface ConversationAction {
        void apply(UUID conversationId, UUID userId);
    }

    private final ConversationService conversationService;

    public ConversationBatchService(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    public BatchConversationResponse archive(List<UUID> ids, UUID userId) {
        return run(ids, userId, conversationService::archiveConversation);
    }

    public BatchConversationResponse unarchive(List<UUID> ids, UUID userId) {
        return run(ids, userId, conversationService::unarchiveConversation);
    }

    public BatchConversationResponse delete(List<UUID> ids, UUID userId) {
        return run(ids, userId, conversationService::deleteConversation);
    }

    BatchConversationResponse run(List<UUID> ids, UUID userId, ConversationAction action) {
        List<Item> results = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            results.add(applyOne(id, userId, action));
        }
        return BatchConversationResponse.of(results);
    }

    private Item applyOne(UUID id, UUID userId, ConversationAction action) {
        try {
            action.apply(id, userId);
            return new Item(id, true, 204, null);
        } catch (ResponseStatusException e) {
            return new Item(id, false, e.getStatusCode().value(), errorCode(e.getStatusCode().value()));
        } catch (YadonyBusinessException e) {
            return new Item(id, false, e.getStatus().value(), e.getErrorCode());
        } catch (RuntimeException e) {
            log.warn("Action groupée en échec pour la conversation {}: {}", id, e.getMessage());
            return new Item(id, false, 500, "unexpected-error");
        }
    }

    private static String errorCode(int status) {
        return switch (status) {
            case 403 -> "access-denied";
            case 404 -> "not-found";
            default -> "request-failed";
        };
    }
}
