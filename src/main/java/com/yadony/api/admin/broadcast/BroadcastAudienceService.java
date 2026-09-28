package com.yadony.api.admin.broadcast;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Resolution paginee des destinataires d'un broadcast. Lecture seule. */
@Service
@Transactional(readOnly = true)
public class BroadcastAudienceService {

    /**
     * 200 destinataires par page. Compromis assume : assez petit pour que la page tienne
     * en memoire et que l'envoi progresse par a-coups visibles, assez grand pour ne pas
     * multiplier les allers-retours SQL. L'envoi FCM restant unitaire
     * ({@code FcmService.sendToToken} boucle sur les jetons), ce nombre borne la memoire,
     * pas le nombre d'appels reseau.
     */
    public static final int PAGE_SIZE = 200;

    private final BroadcastAudienceRepository repository;
    private final UserRepository userRepository;

    public BroadcastAudienceService(BroadcastAudienceRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    /**
     * Compte visé par un ciblage nominatif, pour l'affichage et la garde d'envoi.
     *
     * @param reachable faux pour un compte banni, en attente de suppression ou supprimé :
     *                  la requête d'audience l'écarte, un envoi n'atteindrait personne.
     */
    public record TargetUser(UUID userId, String displayName, boolean reachable) {}

    /**
     * Vide pour un ciblage par segment. Pour {@code USER} : 404 {@code user-not-found} si
     * l'identifiant ne désigne aucune ligne, y compris supprimée (un compte supprimé existe
     * encore, anonymisé, et se signale comme injoignable plutôt qu'inconnu).
     */
    public Optional<TargetUser> targetUser(BroadcastTarget target) {
        if (target.type() != BroadcastTargetType.USER) {
            return Optional.empty();
        }
        UserEntity user = userRepository.findByIdIncludingDeleted(target.userId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "Not Found", "Utilisateur introuvable"));
        boolean reachable = user.getDeletedAt() == null
                && user.getStatus() != UserStatus.BANNED
                && user.getStatus() != UserStatus.PENDING_DELETION;
        return Optional.of(new TargetUser(user.getId(), MatchingTextUtil.buildName(user), reachable));
    }

    public long count(BroadcastTarget target) {
        return page(target, 0).getTotalElements();
    }

    public Page<UUID> page(BroadcastTarget target, int pageNumber) {
        Pageable pageable = PageRequest.of(pageNumber, PAGE_SIZE);
        return switch (target.type()) {
            case ALL -> repository.findActiveIds(pageable);
            case SENDERS -> repository.findActiveSenderIds(pageable);
            case TRAVELERS -> repository.findActiveTravelerIds(pageable);
            case CORRIDOR -> repository.findActiveCorridorIds(
                    target.origin(), target.destination(), pageable);
            case USER -> repository.findExistingIdById(target.userId(), pageable);
        };
    }
}
