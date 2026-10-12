package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.flights.dto.FlightAlertRequest;
import com.yadony.api.flights.dto.FlightAlertResponse;
import com.yadony.api.flights.dto.FlightAlertUpdateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Alertes prix de l'utilisateur : création, pause, seuil, suppression (douce). */
@Service
@Transactional
public class FlightAlertService {

    static final int MAX_ALERTS = 10;

    private final FlightPriceAlertRepository alerts;
    private final FlightUsers users;
    private final Clock clock;

    @Autowired
    public FlightAlertService(FlightPriceAlertRepository alerts, FlightUsers users) {
        this(alerts, users, Clock.systemUTC());
    }

    FlightAlertService(FlightPriceAlertRepository alerts, FlightUsers users, Clock clock) {
        this.alerts = alerts;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<FlightAlertResponse> list(String firebaseUid) {
        UUID owner = users.resolve(firebaseUid).id();
        return alerts.findAllByOwnerIdOrderByCreatedAtDesc(owner).stream().map(FlightAlertService::toResponse).toList();
    }

    public FlightAlertResponse create(String firebaseUid, FlightAlertRequest request) {
        UUID owner = users.resolve(firebaseUid).id();
        FlightQuery query = FlightQuery.of(request.originCode(), request.destinationCode(),
                request.departureMonth(), request.returnMonth(), request.directOnly(), request.currency(), clock);

        List<FlightPriceAlertEntity> existing = alerts.findAllByOwnerIdOrderByCreatedAtDesc(owner);
        if (existing.size() >= MAX_ALERTS) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "flight-alert-limit-reached",
                    "Flight Alert Limit Reached", "Vous avez atteint la limite de " + MAX_ALERTS + " alertes prix.");
        }
        boolean duplicate = existing.stream().anyMatch(a -> a.toQuery().key().equals(query.key()));
        if (duplicate) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "flight-alert-duplicate",
                    "Duplicate Flight Alert", "Une alerte identique existe déjà pour ce trajet.");
        }

        FlightPriceAlertEntity alert = new FlightPriceAlertEntity();
        alert.setOwnerId(owner);
        alert.setOriginCode(query.origin());
        alert.setDestinationCode(query.destination());
        alert.setOriginLabel(trimToNull(request.originLabel()));
        alert.setDestinationLabel(trimToNull(request.destinationLabel()));
        alert.setDepartureMonth(query.departureMonth() == null ? null : query.departureMonth().toString());
        alert.setReturnMonth(query.returnMonth() == null ? null : query.returnMonth().toString());
        alert.setDirectOnly(query.directOnly());
        alert.setMaxPrice(request.maxPrice().setScale(2, RoundingMode.HALF_UP));
        alert.setCurrency(query.currency());
        alert.setActive(true);
        return toResponse(alerts.save(alert));
    }

    public FlightAlertResponse update(String firebaseUid, UUID id, FlightAlertUpdateRequest request) {
        FlightPriceAlertEntity alert = owned(firebaseUid, id);
        if (request.active() != null) {
            alert.setActive(request.active());
        }
        if (request.maxPrice() != null) {
            var newMax = request.maxPrice().setScale(2, RoundingMode.HALF_UP);
            if (!Objects.equals(newMax, alert.getMaxPrice())) {
                alert.setMaxPrice(newMax);
                // Nouveau seuil, nouvelle donne : la prochaine baisse sous ce seuil sera annoncée.
                alert.setLastNotifiedPrice(null);
            }
        }
        return toResponse(alerts.save(alert));
    }

    public void delete(String firebaseUid, UUID id) {
        FlightPriceAlertEntity alert = owned(firebaseUid, id);
        alert.setActive(false);
        alert.softDelete();
        alerts.save(alert);
    }

    /** 404 aussi quand l'alerte existe chez un autre : on ne révèle pas son existence. */
    private FlightPriceAlertEntity owned(String firebaseUid, UUID id) {
        UUID owner = users.resolve(firebaseUid).id();
        return alerts.findByIdAndOwnerId(id, owner)
                .orElseThrow(() -> new YadonyNotFoundException("Flight alert not found"));
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static FlightAlertResponse toResponse(FlightPriceAlertEntity a) {
        return new FlightAlertResponse(a.getId(), a.getOriginCode(), a.getDestinationCode(), a.getOriginLabel(),
                a.getDestinationLabel(), a.getDepartureMonth(), a.getReturnMonth(), a.isDirectOnly(),
                a.getMaxPrice(), a.getCurrency(), a.isActive(), a.getLastSeenPrice(), a.getLastCheckedAt(),
                a.getCreatedAt());
    }
}
