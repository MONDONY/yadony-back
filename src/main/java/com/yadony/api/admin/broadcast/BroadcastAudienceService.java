package com.yadony.api.admin.broadcast;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public BroadcastAudienceService(BroadcastAudienceRepository repository) {
        this.repository = repository;
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
