package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.flights.dto.FlightAlertRequest;
import com.yadony.api.flights.dto.FlightAlertResponse;
import com.yadony.api.flights.dto.FlightAlertUpdateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
        UUID owner = users.resolveAccount(firebaseUid).id();
        return alerts.findAllByOwnerIdOrderByCreatedAtDesc(owner).stream().map(FlightAlertService::toResponse).toList();
    }

    public FlightAlertResponse create(String firebaseUid, FlightAlertRequest request) {
        UUID owner = users.resolveAccount(firebaseUid).id();
        FlightQuery query = FlightQuery.of(request.originCode(), request.destinationCode(),
                null, null, request.directOnly(), request.currency(), clock);
        LocalDate today = LocalDate.now(clock);
        FlightAlertWindow window = window(request, today);

        List<FlightPriceAlertEntity> existing = alerts.findAllByOwnerIdOrderByCreatedAtDesc(owner);
        if (existing.size() >= MAX_ALERTS) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "flight-alert-limit-reached",
                    "Flight Alert Limit Reached", "Vous avez atteint la limite de " + MAX_ALERTS + " alertes prix.");
        }
        boolean duplicate = existing.stream().anyMatch(a -> a.getOriginCode().equals(query.origin())
                && a.getDestinationCode().equals(query.destination())
                && a.isDirectOnly() == query.directOnly()
                && a.getCurrency().equals(query.currency())
                // Vue depuis aujourd'hui : une alerte créée hier « du 10 au 20 » et ramenée
                // au 12 aujourd'hui couvre la même période.
                && a.window().from(today).key().equals(window.key()));
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
        alert.setDepartureFrom(window.departureFrom());
        alert.setDepartureTo(window.departureTo());
        alert.setReturnFrom(window.returnFrom());
        alert.setReturnTo(window.returnTo());
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
        UUID owner = users.resolveAccount(firebaseUid).id();
        return alerts.findByIdAndOwnerId(id, owner)
                .orElseThrow(FlightErrors::alertNotFound);
    }

    /**
     * Période validée : bornes par paire, ordonnées, pas entièrement passées, 31 jours au plus ;
     * une borne de départ déjà passée est ramenée à aujourd'hui.
     */
    private FlightAlertWindow window(FlightAlertRequest r, LocalDate today) {
        LocalDate[] departure = period(r.departureFrom(), r.departureTo(), "départ", today);
        LocalDate[] back = period(r.returnFrom(), r.returnTo(), "retour", today);
        if (departure[0] != null && back[0] != null && back[1].isBefore(departure[0])) {
            throw FlightErrors.invalid("flights-return-before-departure", "Le retour ne peut pas précéder le départ.");
        }
        return new FlightAlertWindow(departure[0], departure[1], back[0], back[1]);
    }

    private static LocalDate[] period(LocalDate from, LocalDate to, String label, LocalDate today) {
        if (from == null && to == null) return new LocalDate[] {null, null};
        if (from == null || to == null) {
            throw FlightErrors.invalid("flights-invalid-period", "La période de " + label + " est incomplète.");
        }
        if (to.isBefore(from)) {
            throw FlightErrors.invalid("flights-invalid-period", "La fin de la période de " + label
                    + " précède son début.");
        }
        if (to.isBefore(today)) {
            throw FlightErrors.invalid("flights-date-past", "La période de " + label + " est déjà passée.");
        }
        LocalDate start = from.isBefore(today) ? today : from;
        if (ChronoUnit.DAYS.between(start, to) + 1 > FlightAlertWindow.MAX_DAYS) {
            throw FlightErrors.invalid("flights-period-too-long", "La période de " + label
                    + " ne peut pas dépasser " + FlightAlertWindow.MAX_DAYS + " jours.");
        }
        return new LocalDate[] {start, to};
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static FlightAlertResponse toResponse(FlightPriceAlertEntity a) {
        return new FlightAlertResponse(a.getId(), a.getOriginCode(), a.getDestinationCode(), a.getOriginLabel(),
                a.getDestinationLabel(), a.getDepartureFrom(), a.getDepartureTo(), a.getReturnFrom(),
                a.getReturnTo(), a.isDirectOnly(),
                a.getMaxPrice(), a.getCurrency(), a.isActive(), a.getLastSeenPrice(), a.getLastCheckedAt(),
                a.getCreatedAt(), a.getLastMatchCount(),
                a.getLastMatchCount() == null ? null : FlightAlertMatches.split(a.getLastMatchAirlines()));
    }
}
