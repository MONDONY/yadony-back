package com.yadony.api.tracking;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TripArrivalInstant;
import com.yadony.api.tracking.dto.TravelerTrackingView;
import com.yadony.api.tracking.dto.TravelerTrackingView.HistoryPoint;
import com.yadony.api.tracking.dto.TravelerTrackingView.TrackingStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lecture du suivi approximatif d'un trajet, du point de vue d'UN colis.
 *
 * <p><b>Fenêtre du lecteur</b> : de l'instant où CE colis a été scanné au départ (événement
 * {@code DEPART}) jusqu'à sa livraison ({@code deliveredAt}), ou {@code now} s'il n'est pas livré.
 * Un échantillon hors de cette fenêtre n'est jamais exposé. Sans scan de départ, la fenêtre est vide.
 *
 * <p><b>Statut</b> (dans cet ordre) :
 * <ol>
 *   <li>{@code ARRIVED} : le colis est {@code ARRIVED} (trajet marqué arrivé) ou {@code COMPLETED} ;</li>
 *   <li>{@code NOT_ACTIVATED} : le départ du trajet n'est pas marqué ({@code departedAt} nul) et la
 *       fenêtre ne contient aucun échantillon ;</li>
 *   <li>{@code STALE} : suivi activé, mais plus de 72 h se sont écoulées depuis l'arrivée prévue
 *       ({@link TripArrivalInstant}) ET aucun échantillon de la fenêtre n'est plus récent que
 *       {@code now - 72 h} ;</li>
 *   <li>{@code EN_ROUTE} sinon.</li>
 * </ol>
 */
@Service
public class TravelerTrackingQuery {

    /** Au-delà de ce délai après l'arrivée prévue, sans échantillon récent, le suivi est « sans nouvelles ». */
    static final Duration STALE_AFTER = Duration.ofHours(72);

    private final TripPositionSampleRepository sampleRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final AnnouncementRepository announcementRepository;
    private final TripArrivalInstant arrivalInstant;

    public TravelerTrackingQuery(TripPositionSampleRepository sampleRepository,
                                 TrackingEventRepository trackingEventRepository,
                                 AnnouncementRepository announcementRepository,
                                 TripArrivalInstant arrivalInstant) {
        this.sampleRepository = sampleRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.announcementRepository = announcementRepository;
        this.arrivalInstant = arrivalInstant;
    }

    /** Vue de l'expéditeur : l'historique de la fenêtre de son colis, dernier point = position actuelle. */
    @Transactional(readOnly = true)
    public TravelerTrackingView forSender(BidEntity bid, OffsetDateTime now) {
        return build(bid, now, true);
    }

    /** Vue de la page publique : dernière position seulement, historique vide. */
    @Transactional(readOnly = true)
    public TravelerTrackingView forPublic(BidEntity bid, OffsetDateTime now) {
        return build(bid, now, false);
    }

    private TravelerTrackingView build(BidEntity bid, OffsetDateTime now, boolean withHistory) {
        Optional<AnnouncementEntity> announcement = announcementRepository.findById(bid.getAnnouncementId());
        List<TripPositionSampleEntity> window = announcement.isPresent()
                ? windowSamples(bid, announcement.get(), now) : List.of();
        TripPositionSampleEntity latest = window.isEmpty() ? null : window.get(window.size() - 1);

        TrackingStatus status = status(bid, announcement.orElse(null), window, now);
        return new TravelerTrackingView(
                status,
                latest != null ? latest.getCapturedAt() : null,
                latest != null ? latest.getProgressPct() : null,
                latest != null ? latest.getCityName() : null,
                withHistory ? collapse(window) : List.of());
    }

    private List<TripPositionSampleEntity> windowSamples(BidEntity bid, AnnouncementEntity a, OffsetDateTime now) {
        Optional<TrackingEventEntity> depart = trackingEventRepository
                .findFirstByBidIdAndEventTypeOrderByScannedAtAsc(bid.getId(), TrackingEventType.DEPART);
        if (depart.isEmpty()) {
            return List.of();
        }
        OffsetDateTime from = depart.get().getScannedAt().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = bid.getDeliveredAt() != null ? bid.getDeliveredAt().atOffset(ZoneOffset.UTC) : now;
        if (to.isBefore(from)) {
            return List.of();
        }
        return sampleRepository.findByAnnouncementIdAndCapturedAtBetweenOrderByCapturedAtAsc(a.getId(), from, to);
    }

    private TrackingStatus status(BidEntity bid, AnnouncementEntity a,
                                  List<TripPositionSampleEntity> window, OffsetDateTime now) {
        if (bid.getStatus() == BidStatus.ARRIVED || bid.getStatus() == BidStatus.COMPLETED) {
            return TrackingStatus.ARRIVED;
        }
        boolean activated = (a != null && a.getDepartedAt() != null) || !window.isEmpty();
        if (!activated) {
            return TrackingStatus.NOT_ACTIVATED;
        }
        Optional<OffsetDateTime> arrival = a != null ? arrivalInstant.of(a) : Optional.empty();
        if (arrival.isPresent() && now.isAfter(arrival.get().plus(STALE_AFTER))) {
            OffsetDateTime freshSince = now.minus(STALE_AFTER);
            boolean recentSample = !window.isEmpty()
                    && window.get(window.size() - 1).getCapturedAt().isAfter(freshSince);
            if (!recentSample) {
                return TrackingStatus.STALE;
            }
        }
        return TrackingStatus.EN_ROUTE;
    }

    /** Villes consécutives identiques regroupées en un point (heure du premier échantillon conservée). */
    private static List<HistoryPoint> collapse(List<TripPositionSampleEntity> window) {
        List<HistoryPoint> points = new ArrayList<>();
        TripPositionSampleEntity previous = null;
        for (TripPositionSampleEntity s : window) {
            boolean sameCity = previous != null
                    && previous.getCityName().equals(s.getCityName())
                    && previous.getCountryCode().equals(s.getCountryCode());
            if (!sameCity) {
                points.add(new HistoryPoint(s.getCityName(), s.getCapturedAt()));
            }
            previous = s;
        }
        return List.copyOf(points);
    }
}
