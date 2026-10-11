package com.yadony.api.alerts;

import com.yadony.api.alerts.dto.TripSpaceAlertResponse;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Abonnements « Me prévenir si de la place se libère » sur un trajet complet (FLUTTER-JH).
 *
 * <p>On ne s'abonne qu'à un trajet que la recherche montre comme complet : statut
 * {@code FULL}, ouvert au public, départ à venir, pas le sien, voyageur non masqué.
 * L'abonnement est idempotent : le redemander renvoie celui qui attend déjà.
 */
@Service
@Transactional
public class TripSpaceAlertService {

    private final TripSpaceAlertRepository repository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final BlockVisibility blockVisibility;

    /** Horloge du « aujourd'hui » du départ ; figée dans les tests. */
    private Clock clock = Clock.systemUTC();

    public TripSpaceAlertService(TripSpaceAlertRepository repository,
                                 AnnouncementRepository announcementRepository,
                                 UserRepository userRepository,
                                 BlockVisibility blockVisibility) {
        this.repository = repository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.blockVisibility = blockVisibility;
    }

    void useClock(Clock clock) {
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TripSpaceAlertResponse> list(String firebaseUid) {
        return repository.findAllBySubscriberIdAndNotifiedAtIsNullOrderByCreatedAtDesc(userId(firebaseUid))
                .stream()
                .map(TripSpaceAlertService::toResponse)
                .toList();
    }

    public TripSpaceAlertResponse subscribe(String firebaseUid, UUID announcementId) {
        UUID subscriberId = userId(firebaseUid);
        AnnouncementEntity trip = announcementRepository.findById(announcementId)
                .filter(AnnouncementEntity::isPubliclyVisible)
                .filter(a -> !blockVisibility.isHidden(subscriberId, a.getTravelerId()))
                .orElseThrow(() -> new YadonyNotFoundException("Trajet introuvable"));
        if (trip.getTravelerId().equals(subscriberId)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "trip-space-alert-own-trip", "Own Trip",
                    "Vous ne pouvez pas vous abonner à votre propre trajet.");
        }
        if (trip.getDepartureDate() != null && trip.getDepartureDate().isBefore(LocalDate.now(clock))) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "trip-departure-passed", "Trip Departure Passed",
                    "Ce trajet est déjà parti.");
        }
        var pending = repository.findFirstByAnnouncementIdAndSubscriberIdAndNotifiedAtIsNull(
                announcementId, subscriberId);
        if (pending.isPresent()) {
            return toResponse(pending.get());
        }
        if (trip.getStatus() != AnnouncementStatus.FULL) {
            // ACTIVE : il y a déjà de la place, la demande se fait directement.
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "trip-not-full", "Trip Not Full",
                    "Ce trajet n'est pas complet.");
        }
        TripSpaceAlertEntity alert = new TripSpaceAlertEntity();
        alert.setAnnouncementId(announcementId);
        alert.setSubscriberId(subscriberId);
        return toResponse(repository.save(alert));
    }

    /** Désabonnement : retrait logique, sans effet s'il n'y a rien à retirer. */
    public void unsubscribe(String firebaseUid, UUID announcementId) {
        repository.findFirstByAnnouncementIdAndSubscriberIdAndNotifiedAtIsNull(announcementId, userId(firebaseUid))
                .ifPresent(alert -> {
                    alert.softDelete();
                    repository.save(alert);
                });
    }

    private UUID userId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyNotFoundException("User not found"))
                .getId();
    }

    private static TripSpaceAlertResponse toResponse(TripSpaceAlertEntity alert) {
        return new TripSpaceAlertResponse(alert.getAnnouncementId(), alert.getCreatedAt());
    }
}
