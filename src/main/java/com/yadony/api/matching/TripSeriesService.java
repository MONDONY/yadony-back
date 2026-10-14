package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.AnnouncementResponse;
import com.yadony.api.matching.dto.TripSeriesRequest;
import com.yadony.api.matching.dto.TripSeriesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Trajet récurrent par dates précises : le voyageur répète un trajet direct à d'autres dates.
 *
 * <p>Chaque date devient une annonce ordinaire, créée par
 * {@link AnnouncementService#createSeriesCopy} dans sa propre transaction (quota de
 * brouillons, KYC, Stripe, audit, événements de publication) : une date refusée n'annule
 * pas les autres et la réponse la détaille. Ce service n'est volontairement pas
 * transactionnel. La récurrence hebdomadaire ({@link TripRecurrenceService}) reste à part.
 */
@Service
public class TripSeriesService {

    private static final Logger log = LoggerFactory.getLogger(TripSeriesService.class);

    /** Une date déjà occupée dans la série par un trajet encore vivant n'est pas recréée. */
    private static final Set<AnnouncementStatus> GONE =
            EnumSet.of(AnnouncementStatus.CANCELLED, AnnouncementStatus.REMOVED_BY_ADMIN);

    private final AnnouncementService announcementService;
    private final AnnouncementRepository announcementRepository;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public TripSeriesService(AnnouncementService announcementService,
                             AnnouncementRepository announcementRepository,
                             AuditService auditService) {
        this(announcementService, announcementRepository, auditService, Clock.systemUTC());
    }

    TripSeriesService(AnnouncementService announcementService,
                      AnnouncementRepository announcementRepository,
                      AuditService auditService,
                      Clock clock) {
        this.announcementService = announcementService;
        this.announcementRepository = announcementRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    public TripSeriesResponse createSeries(String firebaseUid, UUID sourceId, TripSeriesRequest request) {
        TripSeriesStart start = announcementService.startSeries(firebaseUid, sourceId);
        TreeSet<LocalDate> dates = new TreeSet<>(request.dates());
        if (dates.contains(start.sourceDate())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "series-invalid-dates",
                    "Dates invalides", "La date du trajet d'origine ne peut pas être répétée.");
        }

        List<AnnouncementResponse> created = new ArrayList<>();
        List<TripSeriesResponse.Failure> failed = new ArrayList<>();
        UUID travelerId = null;
        for (LocalDate date : dates) {
            if (announcementRepository.existsBySeriesIdAndDepartureDateAndStatusNotIn(
                    start.seriesId(), date, GONE)) {
                failed.add(new TripSeriesResponse.Failure(date, "series-date-taken",
                        "Un trajet de cette série part déjà ce jour-là."));
                continue;
            }
            try {
                AnnouncementResponse copy = announcementService.createSeriesCopy(
                        firebaseUid, shiftTo(start, date, request.saveAsDraft()), start.seriesId());
                created.add(copy);
                travelerId = copy.travelerId();
            } catch (YadonyBusinessException e) {
                failed.add(new TripSeriesResponse.Failure(date, e.getErrorCode(), e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("[TripSeries] copie du {} refusée : {}", date, e.getMessage());
                failed.add(new TripSeriesResponse.Failure(date, "series-copy-failed", null));
            }
        }

        if (travelerId != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sourceId", sourceId.toString());
            payload.put("seriesId", start.seriesId().toString());
            payload.put("created", created.size());
            payload.put("failed", failed.size());
            payload.put("draft", request.saveAsDraft());
            auditService.log("ANNOUNCEMENT", start.seriesId(), "TRIP_SERIES_CREATED", travelerId, payload);
        }
        return new TripSeriesResponse(start.seriesId(), created, failed);
    }

    /**
     * La requête du trajet d'origine, décalée au {@code date} : la date limite de dépôt et
     * la date d'arrivée glissent du même nombre de jours que le départ. Une date limite
     * déjà passée dans le fuseau du départ revient à l'heure du départ, comme pour la
     * récurrence hebdomadaire.
     */
    AnnouncementRequest shiftTo(TripSeriesStart start, LocalDate date, boolean draft) {
        AnnouncementRequest b = start.base();
        long days = ChronoUnit.DAYS.between(start.sourceDate(), date);
        LocalDateTime deadline = b.handoverDeadline() == null ? null : b.handoverDeadline().plusDays(days);
        if (deadline != null && b.departureTime() != null) {
            ZoneId zone = start.zone();
            if (deadline.isBefore(LocalDateTime.now(clock.withZone(zone)))) {
                deadline = date.atTime(b.departureTime());
            }
        }
        LocalDate arrivalDate = b.arrivalDate() == null ? null : b.arrivalDate().plusDays(days);
        return new AnnouncementRequest(
                b.departureCity(), b.arrivalCity(), date, b.departureTime(), b.arrivalTime(),
                b.pickupAddress(), b.deliveryAddress(), b.availableKg(), b.pricePerKg(),
                b.transportMode(), b.description(), b.acceptedContentTypes(), b.refusedTypes(),
                b.acceptedPaymentMethods(), b.capacityUnit(), b.pricingMode(),
                b.departureCountryCode(), b.arrivalCountryCode(), deadline, draft,
                b.negotiable(), b.currency(), arrivalDate, b.stopsCount());
    }
}
