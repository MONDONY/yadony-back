package com.yadony.api.messaging;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import com.yadony.api.common.deletion.UserDeletionImpactContributor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Les fils de discussion encore ouverts côté interlocuteur.
 *
 * <p>Aucune contrepartie n'est nommée ici : une conversation est toujours adossée à une offre,
 * donc les mêmes personnes sont déjà listées par les constats de {@code matching}. Les répéter
 * ferait apparaître deux fois le même nom dans l'écran de suppression.
 */
@Component
public class MessagingDeletionImpactContributor implements UserDeletionImpactContributor {

    private final ConversationRepository conversationRepository;

    public MessagingDeletionImpactContributor(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    @Override
    public List<ImpactFinding> contribute(UUID userId) {
        long active = conversationRepository.countActiveByParticipant(userId);
        if (active == 0) {
            return List.of();
        }
        return List.of(ImpactFinding.plain(
                ImpactSeverity.WARNING, "ACTIVE_CONVERSATION", Math.toIntExact(active)));
    }
}
