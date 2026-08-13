package com.yadony.api.triptemplate;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.config.ContentCategoryNormalizer;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.CurrencyBounds;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.triptemplate.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TripTemplateService {

    private static final Logger log = LoggerFactory.getLogger(TripTemplateService.class);

    private final TripTemplateRepository repository;
    private final AuditService auditService;
    private final ActiveCurrencyResolver activeCurrencyResolver;

    public TripTemplateService(TripTemplateRepository repository, AuditService auditService,
                               ActiveCurrencyResolver activeCurrencyResolver) {
        this.repository = repository;
        this.auditService = auditService;
        this.activeCurrencyResolver = activeCurrencyResolver;
    }

    /**
     * Vérifie que le prix au kilo tient dans les bornes de la devise du voyageur.
     *
     * <p>Les DTO plafonnaient à 500 quelle que soit la devise. En franc CFA cela
     * valait 0,76 €/kg : aucun voyageur en XOF ne pouvait enregistrer un modèle de
     * trajet réaliste. Une annotation Bean Validation ne pouvant pas dépendre de la
     * devise, la règle est appliquée ici.
     */
    private void assertPricePerKgWithinBounds(UUID userId, Double pricePerKg) {
        if (pricePerKg == null) {
            return;
        }
        SupportedCurrency currency =
                SupportedCurrency.fromCodeOrDefault(activeCurrencyResolver.resolve(userId));
        java.math.BigDecimal value = java.math.BigDecimal.valueOf(pricePerKg);
        if (value.compareTo(CurrencyBounds.maxPricePerKg(currency)) > 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "trip-template/price-out-of-bounds");
        }
    }

    public List<TripTemplateDto> findAll(UUID userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    public TripTemplateDto create(UUID userId, CreateTripTemplateRequest request) {
        assertPricePerKgWithinBounds(userId, request.pricePerKg());
        TripTemplateEntity entity = new TripTemplateEntity();
        entity.setUserId(userId);
        applyFields(entity, request.label(), request.emoji(), request.departureCity(),
                request.departureLat(), request.departureLng(), request.arrivalCity(),
                request.arrivalLat(), request.arrivalLng(), request.transportMode(),
                request.capacityUnit(), request.availableKg(), request.pricePerKg(),
                request.acceptedCategories(), request.cashAccepted(), request.arrivalTime());
        repository.save(entity);

        auditService.log("TRIP_TEMPLATE", entity.getId(), "TRIP_TEMPLATE_CREATED", userId,
                Map.of("label", request.label(),
                        "corridor", request.departureCity() + "->" + request.arrivalCity()));
        log.info("TripTemplate created: id={} userId={}", entity.getId(), userId);
        return toDto(entity);
    }

    @Transactional
    public TripTemplateDto update(UUID userId, UUID id, UpdateTripTemplateRequest request) {
        assertPricePerKgWithinBounds(userId, request.pricePerKg());
        TripTemplateEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new YadonyNotFoundException("TripTemplate", id));
        applyFields(entity, request.label(), request.emoji(), request.departureCity(),
                request.departureLat(), request.departureLng(), request.arrivalCity(),
                request.arrivalLat(), request.arrivalLng(), request.transportMode(),
                request.capacityUnit(), request.availableKg(), request.pricePerKg(),
                request.acceptedCategories(), request.cashAccepted(), request.arrivalTime());
        repository.save(entity);

        auditService.log("TRIP_TEMPLATE", entity.getId(), "TRIP_TEMPLATE_UPDATED", userId,
                Map.of("label", request.label()));
        log.info("TripTemplate updated: id={} userId={}", id, userId);
        return toDto(entity);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        TripTemplateEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new YadonyNotFoundException("TripTemplate", id));
        entity.softDelete();
        repository.save(entity);
        auditService.log("TRIP_TEMPLATE", entity.getId(), "TRIP_TEMPLATE_DELETED", userId,
                Map.of("id", id.toString()));
    }

    private void applyFields(TripTemplateEntity entity, String label, String emoji,
                             String departureCity, Double departureLat, Double departureLng,
                             String arrivalCity, Double arrivalLat, Double arrivalLng,
                             String transportMode, String capacityUnit, Integer availableKg,
                             Double pricePerKg, List<String> acceptedCategories,
                             boolean cashAccepted, java.time.LocalTime arrivalTime) {
        entity.setLabel(label);
        entity.setEmoji(emoji);
        entity.setDepartureCity(departureCity);
        entity.setDepartureLat(departureLat);
        entity.setDepartureLng(departureLng);
        entity.setArrivalCity(arrivalCity);
        entity.setArrivalLat(arrivalLat);
        entity.setArrivalLng(arrivalLng);
        entity.setTransportMode(transportMode);
        entity.setCapacityUnit(capacityUnit);
        entity.setAvailableKg(availableKg);
        entity.setPricePerKg(pricePerKg);
        // Normalisé à l'écriture (C2) — un modèle réutilisé pour publier un trajet
        // (TripPublishFromTemplate) doit produire des acceptedCategories déjà canoniques.
        entity.setAcceptedCategories(joinCategories(ContentCategoryNormalizer.normalizeList(acceptedCategories)));
        entity.setCashAccepted(cashAccepted);
        entity.setArrivalTime(arrivalTime);
    }

    private String joinCategories(List<String> categories) {
        if (categories == null || categories.isEmpty()) return null;
        return categories.stream()
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.joining(","));
    }

    private List<String> splitCategories(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return Arrays.stream(joined.split(","))
                .map(String::trim)
                .filter(c -> !c.isEmpty())
                .collect(Collectors.toList());
    }

    private TripTemplateDto toDto(TripTemplateEntity e) {
        return new TripTemplateDto(e.getId(), e.getLabel(), e.getEmoji(),
                e.getDepartureCity(), e.getDepartureLat(), e.getDepartureLng(),
                e.getArrivalCity(), e.getArrivalLat(), e.getArrivalLng(),
                e.getTransportMode(), e.getCapacityUnit(), e.getAvailableKg(), e.getPricePerKg(),
                splitCategories(e.getAcceptedCategories()), e.isCashAccepted(), e.getArrivalTime(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
