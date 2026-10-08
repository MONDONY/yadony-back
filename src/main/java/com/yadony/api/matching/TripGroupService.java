package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.AnnouncementResponse;
import com.yadony.api.matching.dto.TripLegSummary;
import com.yadony.api.matching.dto.TripLegsResponse;
import com.yadony.api.matching.dto.TripRequest;
import com.yadony.api.matching.dto.TripResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Voyage à plusieurs étapes (FLUTTER-4D, option 1 : annonces chaînées).
 *
 * <p>Un voyage n'est pas une entité : c'est N annonces ordinaires qui partagent un
 * {@code trip_group_id}. Recherche, alertes, bids, QR, suivi et paiement restent par
 * annonce ; chaque étape a ses kilos, son prix et ses colis, et aucun colis ne passe
 * d'une étape à l'autre. Annuler une étape n'annule pas les autres.
 */
@Service
public class TripGroupService {

    /** Statuts d'étape cachés aux tiers dans la liste des étapes sœurs. */
    private static final Set<AnnouncementStatus> HIDDEN_FROM_OTHERS =
            EnumSet.of(AnnouncementStatus.DRAFT, AnnouncementStatus.REMOVED_BY_ADMIN);

    private final AnnouncementService announcementService;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final BlockVisibility blockVisibility;
    private final AuditService auditService;

    public TripGroupService(AnnouncementService announcementService,
                            AnnouncementRepository announcementRepository,
                            UserRepository userRepository,
                            BlockVisibility blockVisibility,
                            AuditService auditService) {
        this.announcementService = announcementService;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.blockVisibility = blockVisibility;
        this.auditService = auditService;
    }

    /**
     * Crée toutes les étapes en une transaction : si une seule est refusée (chaînage,
     * prix, date, quota, KYC…), aucune n'est créée. Le refus porte {@code legIndex}.
     */
    @Transactional
    public TripResponse createTrip(String firebaseUid, TripRequest request) {
        List<AnnouncementRequest> legs = request.legs();
        TripLegRules.validate(legs);

        UUID tripGroupId = UUID.randomUUID();
        List<UUID> legIds = new ArrayList<>(legs.size());
        UUID travelerId = null;
        for (int i = 0; i < legs.size(); i++) {
            int legIndex = i + 1;
            AnnouncementResponse created;
            try {
                created = announcementService.createTripLeg(firebaseUid, legs.get(i), tripGroupId, legIndex);
            } catch (YadonyBusinessException e) {
                throw TripLegRules.withLegIndex(e, legIndex);
            }
            legIds.add(created.id());
            travelerId = created.travelerId();
        }

        auditService.log("TRIP_GROUP", tripGroupId, "TRIP_GROUP_CREATED", travelerId, Map.of(
                "legCount", legIds.size(),
                "announcementIds", String.join(",", legIds.stream().map(UUID::toString).toList()),
                "draft", legs.get(0).isDraft()));

        return new TripResponse(tripGroupId, announcementService.toResponses(legIds));
    }

    /**
     * Étapes du voyage d'une annonce, avec les mêmes règles de visibilité que sa fiche :
     * 404 si l'annonce est un brouillon d'un autre ou si son voyageur est bloqué. Les
     * étapes en brouillon ou retirées par la modération ne sont montrées qu'au voyageur.
     */
    @Transactional(readOnly = true)
    public TripLegsResponse getTripLegs(UUID announcementId, String firebaseUid) {
        AnnouncementEntity announcement = announcementRepository.findById(announcementId)
                .orElseThrow(TripGroupService::notFound);
        UUID viewerId = firebaseUid == null ? null
                : userRepository.findByFirebaseUid(firebaseUid).map(UserEntity::getId).orElse(null);
        boolean owner = viewerId != null && viewerId.equals(announcement.getTravelerId());
        if (blockVisibility.isHidden(viewerId, announcement.getTravelerId())) {
            throw notFound();
        }
        if (announcement.getStatus() == AnnouncementStatus.DRAFT && !owner) {
            throw notFound();
        }
        if (announcement.getTripGroupId() == null) {
            return TripLegsResponse.none();
        }
        List<AnnouncementEntity> siblings =
                announcementRepository.findByTripGroupIdOrderByTripLegIndexAsc(announcement.getTripGroupId());
        List<TripLegSummary> visible = siblings.stream()
                .filter(a -> owner || !HIDDEN_FROM_OTHERS.contains(a.getStatus()))
                .map(TripGroupService::toSummary)
                .toList();
        return new TripLegsResponse(announcement.getTripGroupId(), siblings.size(), visible);
    }

    static TripLegSummary toSummary(AnnouncementEntity a) {
        return new TripLegSummary(
                a.getId(),
                a.getTripLegIndex() != null ? a.getTripLegIndex() : 0,
                a.getDepartureCity(),
                a.getArrivalCity(),
                a.getDepartureCountryCode(),
                a.getArrivalCountryCode(),
                a.getDepartureDate(),
                a.getArrivalDate(),
                a.getStatus().name());
    }

    private static YadonyBusinessException notFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found",
                "Announcement Not Found", "Annonce introuvable");
    }
}
