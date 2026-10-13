package com.yadony.api.requests.service;

import com.yadony.api.requests.dto.PackageRequestSearchResponse.MyNegotiation;
import com.yadony.api.requests.entity.NegotiationMessageEntity;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.repository.NegotiationMessageRepository;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Négociations en cours d'un voyageur sur un lot de demandes d'envoi, pour la recherche
 * du mode « Je voyage » (FLUTTER-NZ) : la carte et la liste marquent les demandes où il
 * négocie déjà, et si c'est à lui de jouer.
 *
 * <p>Deux requêtes par page quelle que soit sa taille : les fils actifs du voyageur sur
 * les demandes de la page, puis le dernier message de ces fils. Jamais de N+1.
 *
 * <p>La règle du tour reprend celle du détail d'un fil ({@code NegotiationService#toResponse},
 * {@code isMyTurn}) pour l'état {@code OPEN}, et l'étend aux états d'attente où l'action
 * attendue est connue : lier un trajet et régler la commission reviennent au voyageur,
 * payer (carte ou dépôt mobile money) revient à l'expéditeur.
 */
@Component
public class TravelerNegotiationLookup {

    private final NegotiationThreadRepository threadRepository;
    private final NegotiationMessageRepository messageRepository;

    public TravelerNegotiationLookup(NegotiationThreadRepository threadRepository,
                                     NegotiationMessageRepository messageRepository) {
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * @param travelerId l'appelant ; {@code null} (visiteur) rend une carte vide sans requête
     * @param requestIds les demandes de la page
     * @return demande → négociation active de l'appelant ; absente quand il n'en a pas
     */
    public Map<UUID, MyNegotiation> activeFor(UUID travelerId, Collection<UUID> requestIds) {
        if (travelerId == null || requestIds == null || requestIds.isEmpty()) {
            return Map.of();
        }
        List<NegotiationThreadEntity> threads =
                threadRepository.findActiveByTravelerIdAndPackageRequestIdIn(travelerId, requestIds);
        if (threads.isEmpty()) {
            return Map.of();
        }

        List<UUID> openIds = threads.stream()
                .filter(t -> t.getStatus() == NegotiationThreadStatus.OPEN)
                .map(NegotiationThreadEntity::getId)
                .toList();
        Map<UUID, UUID> lastAuthorByThread = new HashMap<>();
        if (!openIds.isEmpty()) {
            for (NegotiationMessageEntity m : messageRepository.findLastByThreadIdIn(openIds)) {
                lastAuthorByThread.putIfAbsent(m.getThreadId(), m.getFromUserId());
            }
        }

        Map<UUID, MyNegotiation> result = new HashMap<>();
        for (NegotiationThreadEntity t : threads) {
            MyNegotiation mine = new MyNegotiation(t.getId(), t.getStatus(),
                    isTravelerTurn(t, travelerId, lastAuthorByThread.get(t.getId())));
            // Un seul fil actif par (demande, voyageur), index unique V63 : la fusion
            // n'est qu'une garde, elle garde le premier fil rendu.
            result.merge(t.getPackageRequestId(), mine, (a, b) -> a);
        }
        return result;
    }

    static boolean isTravelerTurn(NegotiationThreadEntity t, UUID travelerId, UUID lastAuthor) {
        return switch (t.getStatus()) {
            // Fil ouvert sans message (cas théorique) : rien à quoi répondre.
            case OPEN -> lastAuthor != null && !lastAuthor.equals(travelerId);
            case AWAITING_TRIP, AWAITING_COMMISSION -> true;
            default -> false;
        };
    }
}
