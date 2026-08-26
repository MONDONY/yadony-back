package com.yadony.api.disputes;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import com.yadony.api.common.deletion.UserDeletionImpactContributor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Un litige ouvert dont une partie s'évapore laisse l'autre sans interlocuteur. Ce n'est pas
 * bloquant — l'administrateur peut trancher le litige lui-même — mais il doit savoir qu'il
 * existe avant de supprimer le compte.
 */
@Component
public class DisputeDeletionImpactContributor implements UserDeletionImpactContributor {

    /** {@code DisputeEntity.status} est une chaîne libre en base, pas un enum. */
    private static final String OPEN = "OPEN";

    private final DisputeRepository disputeRepository;

    public DisputeDeletionImpactContributor(DisputeRepository disputeRepository) {
        this.disputeRepository = disputeRepository;
    }

    @Override
    public List<ImpactFinding> contribute(UUID userId) {
        List<DisputeEntity> open =
                disputeRepository.findBySenderIdOrTravelerIdOrderByCreatedAtDesc(userId, userId)
                        .stream()
                        .filter(d -> OPEN.equals(d.getStatus()))
                        .toList();

        if (open.isEmpty()) {
            return List.of();
        }

        // Le compte est d'un côté ou de l'autre selon le litige : on nomme celui qu'il n'est pas.
        List<ImpactFinding.AffectedParty> parties = new ArrayList<>();
        for (DisputeEntity d : open) {
            UUID other = userId.equals(d.getSenderId()) ? d.getTravelerId() : d.getSenderId();
            if (other != null && !other.equals(userId)) {
                parties.add(new ImpactFinding.AffectedParty(other, d.getId()));
            }
        }

        // Le décompte suit les litiges, pas les contreparties : un litige sans autre partie
        // identifiable doit rester visible à l'écran.
        return List.of(new ImpactFinding(
                ImpactSeverity.WARNING, "OPEN_DISPUTE", open.size(), parties));
    }
}
