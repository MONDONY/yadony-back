package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.config.ContentCategoryNormalizer;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.TripRecurrenceDto;
import com.yadony.api.matching.dto.TripRecurrenceRequest;
import com.yadony.api.payments.cash.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TripRecurrenceService {

    private static final Logger log = LoggerFactory.getLogger(TripRecurrenceService.class);

    private final TripRecurrenceRepository repository;
    private final AnnouncementService announcementService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public TripRecurrenceService(TripRecurrenceRepository repository,
                                 AnnouncementService announcementService,
                                 UserRepository userRepository,
                                 AuditService auditService) {
        this.repository = repository;
        this.announcementService = announcementService;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    public List<TripRecurrenceDto> findAll(UUID userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    public TripRecurrenceDto create(UUID userId, TripRecurrenceRequest request) {
        TripRecurrenceEntity entity = new TripRecurrenceEntity();
        entity.setUserId(userId);
        applyFields(entity, request);
        repository.save(entity);
        auditService.log("TRIP_RECURRENCE", entity.getId(), "TRIP_RECURRENCE_CREATED", userId,
                Map.of("corridor", request.departureCity() + "->" + request.arrivalCity(),
                        "weekdays", request.weekdays()));
        log.info("TripRecurrence created: id={} userId={}", entity.getId(), userId);
        // Génère immédiatement les trajets dus pour ne pas attendre le scheduler.
        if (entity.isActive()) {
            generateForRecurrence(entity);
        }
        return toDto(entity);
    }

    @Transactional
    public TripRecurrenceDto update(UUID userId, UUID id, TripRecurrenceRequest request) {
        TripRecurrenceEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new YadonyNotFoundException("TripRecurrence", id));
        applyFields(entity, request);
        repository.save(entity);
        auditService.log("TRIP_RECURRENCE", entity.getId(), "TRIP_RECURRENCE_UPDATED", userId,
                Map.of("active", String.valueOf(request.active())));
        if (entity.isActive()) {
            generateForRecurrence(entity);
        }
        return toDto(entity);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        TripRecurrenceEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new YadonyNotFoundException("TripRecurrence", id));
        entity.softDelete();
        repository.save(entity);
        auditService.log("TRIP_RECURRENCE", entity.getId(), "TRIP_RECURRENCE_DELETED", userId,
                Map.of("id", id.toString()));
    }

    /** Appelé par le scheduler : génère les trajets dus pour toutes les récurrences actives. */
    public int generateDueTrips() {
        List<TripRecurrenceEntity> active = repository.findByActiveTrue();
        int total = 0;
        for (TripRecurrenceEntity rec : active) {
            total += generateForRecurrence(rec);
        }
        if (total > 0) {
            log.info("TripRecurrence scheduler: {} trajet(s) généré(s) sur {} récurrence(s)", total, active.size());
        }
        return total;
    }

    /**
     * Génère les annonces dues pour une récurrence, dans la fenêtre
     * ]lastGeneratedDate, today+horizon], pour les jours de semaine cochés.
     * Chaque création est isolée (try/catch) : un échec (limite PRO, KYC…)
     * n'interrompt pas les autres.
     */
    int generateForRecurrence(TripRecurrenceEntity rec) {
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(rec.getHorizonDays());
        LocalDate start = rec.getLastGeneratedDate() == null
                ? today
                : rec.getLastGeneratedDate().plusDays(1);
        if (start.isBefore(today)) {
            start = today;
        }
        if (start.isAfter(end)) {
            return 0; // déjà généré jusqu'à l'horizon
        }

        UserEntity user = userRepository.findById(rec.getUserId()).orElse(null);
        if (user == null) {
            log.warn("TripRecurrence {} : utilisateur {} introuvable, génération ignorée", rec.getId(), rec.getUserId());
            return 0;
        }
        String firebaseUid = user.getFirebaseUid();

        int created = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            int idx = d.getDayOfWeek().getValue() - 1; // Lundi=0 .. Dimanche=6
            if (rec.getWeekdays().charAt(idx) != '1') {
                continue;
            }
            try {
                announcementService.createAnnouncement(firebaseUid, buildRequest(rec, d));
                created++;
            } catch (Exception e) {
                log.warn("TripRecurrence {} : échec création trajet {} : {}", rec.getId(), d, e.getMessage());
            }
        }

        rec.setLastGeneratedDate(end);
        repository.save(rec);
        return created;
    }

    private AnnouncementRequest buildRequest(TripRecurrenceEntity rec, LocalDate date) {
        Set<PaymentMethod> paymentMethods = rec.isCashAccepted()
                ? Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH)
                : Set.of(PaymentMethod.STRIPE);
        LocalTime depTime = rec.getDepartureTime();
        LocalDateTime departureDt = depTime != null
                ? date.atTime(depTime) : date.atTime(12, 0);
        // Date limite de dépôt d'un trajet récurrent : l'heure du départ.
        LocalDateTime handoverDeadline = departureDt;
        return new AnnouncementRequest(
                rec.getDepartureCity(),
                rec.getArrivalCity(),
                date,
                rec.getDepartureTime(),
                rec.getArrivalTime(),
                new AddressDto(rec.getPickupLabel(), rec.getPickupLat(), rec.getPickupLng()),
                new AddressDto(rec.getDeliveryLabel(), rec.getDeliveryLat(), rec.getDeliveryLng()),
                BigDecimal.valueOf(rec.getAvailableKg()),
                BigDecimal.valueOf(rec.getPricePerKg()),
                TransportMode.valueOf(rec.getTransportMode()),
                null,
                splitCategories(rec.getAcceptedCategories()),
                List.of(),
                paymentMethods,
                CapacityUnit.valueOf(rec.getCapacityUnit()),
                PricingMode.KG,
                null,
                null,
                handoverDeadline,
                Boolean.FALSE,
                Boolean.FALSE,
                null
        );
    }

    private void applyFields(TripRecurrenceEntity e, TripRecurrenceRequest r) {
        e.setSourceTemplateId(r.sourceTemplateId());
        e.setDepartureCity(r.departureCity());
        e.setArrivalCity(r.arrivalCity());
        e.setTransportMode(r.transportMode());
        e.setCapacityUnit(r.capacityUnit());
        e.setAvailableKg(r.availableKg());
        e.setPricePerKg(r.pricePerKg());
        // Normalisé à l'écriture (C2) : sinon, chaque exécution du scheduler
        // (generateForRecurrence → announcementService.createAnnouncement) ré-injecte
        // des libellés legacy dans announcement_accepted_types que V171 vient de normaliser.
        e.setAcceptedCategories(joinCategories(ContentCategoryNormalizer.normalizeList(r.acceptedCategories())));
        e.setPickupLabel(r.pickupAddress().label());
        e.setPickupLat(r.pickupAddress().lat());
        e.setPickupLng(r.pickupAddress().lng());
        e.setDeliveryLabel(r.deliveryAddress().label());
        e.setDeliveryLat(r.deliveryAddress().lat());
        e.setDeliveryLng(r.deliveryAddress().lng());
        e.setDepartureTime(r.departureTime());
        e.setArrivalTime(r.arrivalTime());
        e.setCashAccepted(r.cashAccepted());
        e.setWeekdays(r.weekdays());
        e.setHorizonDays(r.horizonDays() != null ? r.horizonDays() : 14);
        e.setActive(r.active());
    }

    private String joinCategories(List<String> categories) {
        if (categories == null || categories.isEmpty()) return null;
        return categories.stream().filter(c -> c != null && !c.isBlank())
                .collect(Collectors.joining(","));
    }

    private List<String> splitCategories(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return Arrays.stream(joined.split(",")).map(String::trim)
                .filter(c -> !c.isEmpty()).collect(Collectors.toList());
    }

    private TripRecurrenceDto toDto(TripRecurrenceEntity e) {
        return new TripRecurrenceDto(
                e.getId(), e.getSourceTemplateId(), e.getDepartureCity(), e.getArrivalCity(),
                e.getTransportMode(), e.getCapacityUnit(), e.getAvailableKg(), e.getPricePerKg(),
                splitCategories(e.getAcceptedCategories()),
                new AddressDto(e.getPickupLabel(), e.getPickupLat(), e.getPickupLng()),
                new AddressDto(e.getDeliveryLabel(), e.getDeliveryLat(), e.getDeliveryLng()),
                e.getDepartureTime(), e.getArrivalTime(), e.isCashAccepted(),
                e.getWeekdays(), e.getHorizonDays(), e.isActive(),
                e.getLastGeneratedDate(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
