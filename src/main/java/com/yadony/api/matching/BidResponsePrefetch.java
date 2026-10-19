package com.yadony.api.matching;

import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.matching.dto.BidPhotoResponse;
import com.yadony.api.matching.reception.BidRecipientLinkEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lectures d'une LISTE de colis faites en quelques requêtes groupées ({@code IN (...)})
 * au lieu d'une série de requêtes par colis dans {@link BidService#toResponse}.
 *
 * <p>YADONY-BACK-STAGING-W (test de charge du 08/10) : {@code GET /bids/me} sans
 * pagination, sur un compte de 741 colis, tenait sa connexion JDBC jusqu'à 125 s
 * ({@code hikaricp_connections_usage_seconds_max} = 124,5 s, max de la route 125,2 s) :
 * environ dix requêtes par colis (lien destinataire, deux notes, annulations, articles
 * de grille, photos, bon de réduction…), soit plusieurs milliers d'allers-retours dans
 * une seule transaction, sur une machine dont le CPU était saturé. Chaque appel retirait
 * une connexion au pool de 20 pendant deux minutes.
 *
 * <p>Les valeurs sont celles que les lectures unitaires renverraient : même filtre de
 * soft delete (les entités portent leur restriction), mêmes statuts, même ordre des
 * photos. Le trajet et les utilisateurs ne sont pas copiés ici : ils sont chargés une
 * fois dans le contexte de persistance de la transaction, où les {@code findById}
 * suivants les trouvent sans requête.
 */
final class BidResponsePrefetch {

    /** Taille d'un lot {@code IN (...)} : bien sous la limite de paramètres de Postgres. */
    static final int CHUNK_SIZE = 500;

    private final Map<UUID, BidRecipientLinkEntity> recipientLinks;
    private final Set<String> bidRaterPairs;
    private final Map<UUID, List<CancellationEntity>> cancellations;
    private final Map<UUID, List<BidGridItemEntity>> gridItems;
    private final Map<UUID, List<BidPhotoResponse>> photos;
    /** Suppléments de la liste ({@link BidSupplementSummaryPort#prefetch}), null hors port. */
    private final BidSupplementSummaryPort.Prefetch supplements;
    /** Taux de commission résolus pendant la conversion, par couple voyageur/expéditeur. */
    private final Map<String, BigDecimal> commissionRates = new HashMap<>();

    BidResponsePrefetch(Map<UUID, BidRecipientLinkEntity> recipientLinks,
                        Set<String> bidRaterPairs,
                        Map<UUID, List<CancellationEntity>> cancellations,
                        Map<UUID, List<BidGridItemEntity>> gridItems,
                        Map<UUID, List<BidPhotoResponse>> photos) {
        this(recipientLinks, bidRaterPairs, cancellations, gridItems, photos, null);
    }

    BidResponsePrefetch(Map<UUID, BidRecipientLinkEntity> recipientLinks,
                        Set<String> bidRaterPairs,
                        Map<UUID, List<CancellationEntity>> cancellations,
                        Map<UUID, List<BidGridItemEntity>> gridItems,
                        Map<UUID, List<BidPhotoResponse>> photos,
                        BidSupplementSummaryPort.Prefetch supplements) {
        this.supplements = supplements;
        this.recipientLinks = recipientLinks;
        this.bidRaterPairs = bidRaterPairs;
        this.cancellations = cancellations;
        this.gridItems = gridItems;
        this.photos = photos;
    }

    BidRecipientLinkEntity recipientLink(UUID bidId) {
        return recipientLinks.get(bidId);
    }

    boolean hasRated(UUID bidId, UUID raterId) {
        return raterId != null && bidRaterPairs.contains(pairKey(bidId, raterId));
    }

    List<CancellationEntity> cancellations(UUID bidId) {
        return cancellations.getOrDefault(bidId, List.of());
    }

    List<BidGridItemEntity> gridItems(UUID bidId) {
        return gridItems.getOrDefault(bidId, List.of());
    }

    BidSupplementSummaryPort.Prefetch supplements() {
        return supplements;
    }

    List<BidPhotoResponse> photos(UUID bidId) {
        return photos.getOrDefault(bidId, List.of());
    }

    /**
     * Le taux ne dépend que du couple voyageur/expéditeur (réglage global, surcharges des
     * deux comptes, bon actif de l'expéditeur) : une seule résolution par couple et par
     * liste, au lieu d'une par colis.
     */
    BigDecimal commissionRate(UUID travelerId, UUID senderId, Supplier<BigDecimal> resolver) {
        String key = travelerId + "|" + senderId;
        BigDecimal cached = commissionRates.get(key);
        if (cached == null && !commissionRates.containsKey(key)) {
            cached = resolver.get();
            commissionRates.put(key, cached);
        }
        return cached;
    }

    static String pairKey(UUID bidId, UUID raterId) {
        return bidId + "|" + raterId;
    }

    /** Applique {@code loader} par lots de {@link #CHUNK_SIZE} identifiants distincts. */
    static <T> List<T> loadInChunks(Collection<UUID> ids, Function<List<UUID>, List<T>> loader) {
        List<UUID> distinct = new ArrayList<>(new HashSet<>(ids));
        List<T> all = new ArrayList<>();
        for (int from = 0; from < distinct.size(); from += CHUNK_SIZE) {
            all.addAll(loader.apply(distinct.subList(from, Math.min(from + CHUNK_SIZE, distinct.size()))));
        }
        return all;
    }
}
