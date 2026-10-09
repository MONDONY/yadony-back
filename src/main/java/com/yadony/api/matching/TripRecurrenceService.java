package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.config.ContentCategoryNormalizer;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.AnnouncementResponse;
import com.yadony.api.matching.dto.TripRecurrenceDto;
import com.yadony.api.matching.dto.TripRecurrenceRequest;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.CurrencyBounds;
import com.yadony.api.payments.currency.CurrencyPaymentRails;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TripRecurrenceService {

    private static final Logger log = LoggerFactory.getLogger(TripRecurrenceService.class);

    private final TripRecurrenceRepository repository;
    private final AnnouncementService announcementService;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final ActiveCurrencyResolver activeCurrencyResolver;
    private final TripRecurrenceCalendar calendar = new TripRecurrenceCalendar();

    public TripRecurrenceService(TripRecurrenceRepository repository,
                                 AnnouncementService announcementService,
                                 AnnouncementRepository announcementRepository,
                                 UserRepository userRepository,
                                 AuditService auditService,
                                 ApplicationEventPublisher eventPublisher,
                                 ActiveCurrencyResolver activeCurrencyResolver) {
        this.repository = repository;
        this.announcementService = announcementService;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.activeCurrencyResolver = activeCurrencyResolver;
    }

    public List<TripRecurrenceDto> findAll(UUID userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    public TripRecurrenceDto create(UUID userId, TripRecurrenceRequest request) {
        TripRecurrenceEntity entity = new TripRecurrenceEntity();
        entity.setUserId(userId);
        // Devise absente (client antérieur) : celle du voyageur, comme un trajet simple.
        applyFields(entity, request, () -> activeCurrencyResolver.resolve(userId));
        repository.save(entity);
        auditService.log("TRIP_RECURRENCE", entity.getId(), "TRIP_RECURRENCE_CREATED", userId,
                Map.of("corridor", request.departureCity() + "->" + request.arrivalCity(),
                        "weekdays", request.weekdays(),
                        "cardDeclined", entity.isCardDeclined()));
        log.info("TripRecurrence created: id={} userId={}", entity.getId(), userId);
        // Génère les trajets dus sans attendre le scheduler, juste après le commit
        // (voir TripRecurrenceGenerationListener).
        if (entity.isActive()) {
            eventPublisher.publishEvent(new TripRecurrenceSavedEvent(entity.getId()));
        }
        return toDto(entity);
    }

    @Transactional
    public TripRecurrenceDto update(UUID userId, UUID id, TripRecurrenceRequest request) {
        TripRecurrenceEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new YadonyNotFoundException("TripRecurrence", id));
        // Devise absente : la récurrence garde la sienne.
        applyFields(entity, request, entity::getCurrency);
        repository.save(entity);
        auditService.log("TRIP_RECURRENCE", entity.getId(), "TRIP_RECURRENCE_UPDATED", userId,
                Map.of("active", String.valueOf(request.active()),
                        "cardDeclined", entity.isCardDeclined()));
        if (entity.isActive()) {
            eventPublisher.publishEvent(new TripRecurrenceSavedEvent(entity.getId()));
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

    /**
     * Génère les trajets dus d'une récurrence encore active. Appelé hors transaction, après
     * le commit de sa création ou de sa modification.
     */
    int generateForRecurrenceId(UUID recurrenceId) {
        return repository.findById(recurrenceId)
                .filter(TripRecurrenceEntity::isActive)
                .map(this::generateForRecurrence)
                .orElse(0);
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
            if (announcementRepository.existsBySourceRecurrenceIdAndDepartureDate(rec.getId(), d)) {
                continue;
            }
            try {
                AnnouncementResponse occurrence = announcementService.createRecurringAnnouncement(
                        firebaseUid, buildRequest(rec, d, user), rec.getId());
                markCardDeclined(rec, occurrence);
                created++;
            } catch (DataIntegrityViolationException exception) {
                if (!isDuplicateOccurrence(exception)) {
                    throw exception;
                }
                log.info("TripRecurrence {} : trajet {} déjà publié", rec.getId(), d);
            } catch (Exception e) {
                log.warn("TripRecurrence {} : échec création trajet {} : {}", rec.getId(), d, e.getMessage());
            }
        }

        rec.setLastGeneratedDate(end);
        repository.save(rec);
        return created;
    }

    /**
     * Recopie le refus de la carte sur l'occurrence générée (FLUTTER-FT). La publication ne le
     * déduit que si le compte Stripe Connect est actif à cet instant ({@code declinesCard}) :
     * générée pendant une restriction du compte, l'occurrence se rouvrirait sinon à la carte
     * au retour de l'onboarding (FLUTTER-DH), malgré le refus de la récurrence.
     */
    private void markCardDeclined(TripRecurrenceEntity rec, AnnouncementResponse occurrence) {
        if (!rec.isCardDeclined() || occurrence == null || occurrence.id() == null
                || !CurrencyPaymentRails.allowsCode(rec.getCurrency(), PaymentMethod.STRIPE)) {
            return;
        }
        announcementRepository.findById(occurrence.id())
                .filter(announcement -> !announcement.isCardDeclined())
                .ifPresent(announcement -> {
                    announcement.setCardDeclined(true);
                    announcementRepository.save(announcement);
                });
    }

    private boolean isDuplicateOccurrence(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains("uq_announcements_recurrence_departure")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private AnnouncementRequest buildRequest(TripRecurrenceEntity rec, LocalDate date, UserEntity user) {
        // Carte et mobile money sont proposés dès que la devise et les comptes du voyageur
        // le permettent (comme la carte l'était déjà) ; l'espèce suit le choix explicite de
        // la récurrence, la carte aussi quand le voyageur l'a décochée (FLUTTER-FT). Jamais
        // vide : sans rail restant, l'espèce garde le trajet vendable.
        EnumSet<PaymentMethod> wanted = EnumSet.of(PaymentMethod.MOBILE_MONEY);
        if (!rec.isCardDeclined()) {
            wanted.add(PaymentMethod.STRIPE);
        }
        if (rec.isCashAccepted()) {
            wanted.add(PaymentMethod.CASH);
        }
        Set<PaymentMethod> paymentMethods = com.yadony.api.payments.currency.AnnouncementPaymentRails
                .offerable(wanted, rec.getCurrency(), user.hasActiveStripeConnect(), user.hasActiveMobileMoney());
        if (paymentMethods.isEmpty()) {
            paymentMethods = EnumSet.of(PaymentMethod.CASH);
        }
        LocalTime depTime = rec.getDepartureTime();
        LocalDateTime departureDt = depTime != null
                ? date.atTime(depTime) : date.atTime(12, 0);
        // Remise au plus tard N jours avant le départ (0 = à l'heure du départ), champ stocké
        // depuis le lot mobile money mais jamais appliqué jusqu'ici.
        int leadDays = rec.getHandoverLeadDays() == null ? 0 : rec.getHandoverLeadDays();
        LocalDateTime handoverDeadline = departureDt.minusDays(leadDays);
        // Une limite déjà expirée à la publication (départ proche, délai de remise long)
        // ouvrirait le signalement de no-show dès l'acceptation du bid : repli sur l'heure
        // du départ, comportement historique d'avant l'introduction du délai de remise.
        // Comparée à l'heure locale de la ville de départ : c'est dans ce fuseau que le trajet
        // généré lira sa date limite (TripTimezones), pas dans celui du serveur.
        java.time.ZoneId departureZone = TripTimezones.zoneOf(TripTimezones.resolve(
                rec.getDepartureCity(), null, TripTimezones.Lookup.of(announcementRepository)));
        if (handoverDeadline.isBefore(LocalDateTime.now(departureZone))) {
            handoverDeadline = departureDt;
        }
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
                rec.getDescription(),
                splitCategories(rec.getAcceptedCategories()),
                splitCategories(rec.getRefusedCategories()),
                paymentMethods,
                CapacityUnit.valueOf(rec.getCapacityUnit()),
                rec.getPricingMode() == null ? PricingMode.KG : rec.getPricingMode(),
                null,
                null,
                handoverDeadline,
                Boolean.FALSE,
                rec.isNegotiable(),
                rec.getCurrency(),
                // Vol de nuit mémorisé dans la récurrence : la date d'arrivée suit chaque
                // occurrence. Nulle le même jour, comme un trajet saisi à la main.
                rec.getArrivalDayOffset() > 0 ? date.plusDays(rec.getArrivalDayOffset()) : null,
                rec.getStopsCount()
        );
    }

    private void applyFields(TripRecurrenceEntity e, TripRecurrenceRequest r,
                             java.util.function.Supplier<String> fallbackCurrency) {
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
        e.setRefusedCategories(joinCategories(ContentCategoryNormalizer.normalizeList(r.refusedCategories())));
        e.setDescription(r.description());
        e.setPickupLabel(r.pickupAddress().label());
        e.setPickupLat(r.pickupAddress().lat());
        e.setPickupLng(r.pickupAddress().lng());
        e.setDeliveryLabel(r.deliveryAddress().label());
        e.setDeliveryLat(r.deliveryAddress().lat());
        e.setDeliveryLng(r.deliveryAddress().lng());
        e.setDepartureTime(r.departureTime());
        e.setArrivalTime(r.arrivalTime());
        e.setArrivalDayOffset(r.arrivalDayOffset() == null ? 0 : r.arrivalDayOffset());
        e.setStopsCount(TripStops.normalize(r.stopsCount(), parseTransportMode(r.transportMode())));
        e.setCashAccepted(r.cashAccepted());
        e.setWeekdays(r.weekdays());
        int publicationLeadDays = r.publicationLeadDays() != null
                ? r.publicationLeadDays()
                : r.horizonDays() != null ? r.horizonDays() : 14;
        e.setHorizonDays(r.horizonDays() != null ? r.horizonDays() : publicationLeadDays);
        e.setStartDate(r.startDate() != null ? r.startDate() : LocalDate.now());
        e.setEndDate(r.endDate());
        e.setWeekInterval(r.weekInterval() != null ? r.weekInterval() : 1);
        e.setPublicationLeadDays(publicationLeadDays);
        e.setHandoverLeadDays(r.handoverLeadDays() != null ? r.handoverLeadDays() : 0);
        e.setPricingMode(r.pricingMode() != null ? r.pricingMode() : PricingMode.KG);
        e.setNegotiable(Boolean.TRUE.equals(r.negotiable()));
        // Même règle qu'un trajet simple (AnnouncementService#resolveAnnouncementCurrency) :
        // devise envoyée validée, sinon repli. Le repli figé sur l'euro enregistrait un modèle
        // XOF/XAF en euros : prix, carte et mobile money des trajets générés jugés sur l'euro.
        SupportedCurrency currency = r.currency() == null || r.currency().isBlank()
                ? SupportedCurrency.fromCodeOrDefault(fallbackCurrency.get())
                : SupportedCurrency.fromCode(r.currency());
        if (currency == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "currency-unsupported", "Currency Unsupported",
                    "Cette devise n'est pas prise en charge par yadony.");
        }
        // Plafond du prix au kilo dans la devise de la récurrence, comme pour un modèle : le
        // DTO ne garde qu'un garde-fou large (500 refusait tout prix réaliste en franc CFA).
        if (r.pricePerKg() != null
                && BigDecimal.valueOf(r.pricePerKg()).compareTo(CurrencyBounds.maxPricePerKg(currency)) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "price-out-of-bounds", "Price Out Of Bounds",
                    "Ce prix au kilo dépasse le plafond autorisé dans cette devise.");
        }
        e.setCurrency(currency.code().toUpperCase(Locale.ROOT));
        e.setActive(r.active());
        // Au moins un moyen de paiement (FLUTTER-FT) : dans une devise où la carte est possible,
        // l'espèce doit rester cochée sans elle, comme sur un trajet simple où elle est imposée.
        if (r.declinesCard() && !r.cashAccepted()
                && CurrencyPaymentRails.allowsCode(e.getCurrency(), PaymentMethod.STRIPE)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "payment-method-required", "Payment Method Required",
                    "Choisissez au moins un moyen de paiement pour ce trajet.");
        }
        e.setCardDeclined(r.declinesCard());
    }

    /** Mode inconnu : aucune escale enregistrée, la validation du mode reste à la publication. */
    private static TransportMode parseTransportMode(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return TransportMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        return toDto(e, LocalDate.now());
    }

    TripRecurrenceDto toDto(TripRecurrenceEntity e, LocalDate today) {
        Optional<TripRecurrenceCalendar.OccurrenceDate> nextOccurrence = nextOccurrence(e, today);
        TripRecurrenceStatus status = statusOf(e, today, nextOccurrence);
        return new TripRecurrenceDto(
                e.getId(), e.getSourceTemplateId(), e.getDepartureCity(), e.getArrivalCity(),
                e.getTransportMode(), e.getCapacityUnit(), e.getAvailableKg(), e.getPricePerKg(),
                e.getPricingMode(), e.isNegotiable(), e.getCurrency(), e.getDescription(),
                splitCategories(e.getAcceptedCategories()), splitCategories(e.getRefusedCategories()),
                new AddressDto(e.getPickupLabel(), e.getPickupLat(), e.getPickupLng()),
                new AddressDto(e.getDeliveryLabel(), e.getDeliveryLat(), e.getDeliveryLng()),
                e.getDepartureTime(), e.getArrivalTime(), e.isCashAccepted(),
                e.getWeekdays(), e.getHorizonDays(), e.getStartDate(), e.getEndDate(),
                e.getWeekInterval(), e.getPublicationLeadDays(), e.getHandoverLeadDays(), e.isActive(),
                e.getArrivalDayOffset(),
                e.getStopsCount(),
                !e.isCardDeclined(),
                e.getLastGeneratedDate(), e.getLastPublicationErrorCode(),
                e.getLastPublicationErrorMessage(), e.getLastPublicationErrorAt(), status,
                nextOccurrence.map(TripRecurrenceCalendar.OccurrenceDate::departureDate).orElse(null),
                nextOccurrence.map(TripRecurrenceCalendar.OccurrenceDate::publicationDate).orElse(null),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private Optional<TripRecurrenceCalendar.OccurrenceDate> nextOccurrence(
            TripRecurrenceEntity recurrence,
            LocalDate today
    ) {
        try {
            var schedule = new TripRecurrenceCalendar.Schedule(
                    recurrence.getStartDate(), recurrence.getEndDate(), recurrence.getWeekdays(),
                    recurrence.getWeekInterval(), recurrence.getPublicationLeadDays());
            return calendar.nextOccurrences(today, 1, schedule).stream().findFirst();
        } catch (IllegalArgumentException | NullPointerException exception) {
            log.warn("TripRecurrence {} : programmation calendaire invalide", recurrence.getId());
            return Optional.empty();
        }
    }

    private TripRecurrenceStatus statusOf(
            TripRecurrenceEntity recurrence,
            LocalDate today,
            Optional<TripRecurrenceCalendar.OccurrenceDate> nextOccurrence
    ) {
        if (recurrence.getEndDate() != null && recurrence.getEndDate().isBefore(today)) {
            return TripRecurrenceStatus.TERMINATED;
        }
        if (!recurrence.isActive()) {
            return TripRecurrenceStatus.PAUSED;
        }
        if (recurrence.getLastPublicationErrorCode() != null) {
            return TripRecurrenceStatus.ACTION_REQUIRED;
        }
        if (nextOccurrence.map(TripRecurrenceCalendar.OccurrenceDate::publicationDate)
                .filter(date -> date.isAfter(today)).isPresent()) {
            return TripRecurrenceStatus.UPCOMING;
        }
        return TripRecurrenceStatus.ACTIVE;
    }
}
