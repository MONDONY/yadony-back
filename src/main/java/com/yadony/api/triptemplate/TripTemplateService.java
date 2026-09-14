package com.yadony.api.triptemplate;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.config.ContentCategoryNormalizer;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.CurrencyBounds;
import com.yadony.api.payments.currency.CurrencyPaymentRails;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.triptemplate.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TripTemplateService {

    private static final Logger log = LoggerFactory.getLogger(TripTemplateService.class);

    private static final Set<PaymentMethod> ALLOWED_METHODS =
            EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY);

    private final TripTemplateRepository repository;
    private final AuditService auditService;
    private final ActiveCurrencyResolver activeCurrencyResolver;

    public TripTemplateService(TripTemplateRepository repository, AuditService auditService,
                               ActiveCurrencyResolver activeCurrencyResolver) {
        this.repository = repository;
        this.auditService = auditService;
        this.activeCurrencyResolver = activeCurrencyResolver;
    }

    public List<TripTemplateDto> findAll(UUID userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    public TripTemplateDto create(UUID userId, CreateTripTemplateRequest request) {
        TripTemplateEntity entity = new TripTemplateEntity();
        entity.setUserId(userId);
        applyFields(userId, entity, request);
        repository.save(entity);

        auditService.log("TRIP_TEMPLATE", entity.getId(), "TRIP_TEMPLATE_CREATED", userId,
                Map.of("label", request.label(),
                        "corridor", request.departureCity() + "->" + request.arrivalCity()));
        log.info("TripTemplate created: id={} userId={}", entity.getId(), userId);
        return toDto(entity);
    }

    @Transactional
    public TripTemplateDto update(UUID userId, UUID id, UpdateTripTemplateRequest request) {
        TripTemplateEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new YadonyNotFoundException("TripTemplate", id));
        applyFields(userId, entity, request);
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

    /**
     * Valide puis copie la requête dans l'entité. La devise du modèle prime sur la devise
     * active du profil pour toutes les règles qui en dépendent (plafond du prix, rails de
     * paiement) : un modèle « Abidjan → Paris » en XOF doit accepter 2 000 F CFA/kg et le
     * mobile money même si le portefeuille est en euros.
     */
    private void applyFields(UUID userId, TripTemplateEntity entity, TripTemplatePayload r) {
        SupportedCurrency currency = resolveCurrency(userId, r.currency());
        String pricingMode = r.pricingMode() == null ? "KG" : r.pricingMode();
        Set<PaymentMethod> methods = resolvePaymentMethods(r.acceptedPaymentMethods(), r.cashAccepted(), currency);
        assertPricePerKg(r.pricePerKg(), pricingMode, currency);
        assertAddressCompleteOrAbsent(r.pickupAddress());
        assertAddressCompleteOrAbsent(r.deliveryAddress());

        entity.setLabel(r.label());
        entity.setEmoji(r.emoji());
        entity.setDepartureCity(r.departureCity());
        entity.setDepartureLat(r.departureLat());
        entity.setDepartureLng(r.departureLng());
        entity.setArrivalCity(r.arrivalCity());
        entity.setArrivalLat(r.arrivalLat());
        entity.setArrivalLng(r.arrivalLng());
        entity.setTransportMode(r.transportMode());
        entity.setCapacityUnit(r.capacityUnit());
        entity.setAvailableKg(r.availableKg());
        entity.setPricePerKg(r.pricePerKg());
        // Normalisé à l'écriture (C2) — un modèle réutilisé pour publier un trajet
        // doit produire des catégories déjà canoniques.
        entity.setAcceptedCategories(joinCategories(ContentCategoryNormalizer.normalizeList(r.acceptedCategories())));
        entity.setRefusedTypes(joinCategories(ContentCategoryNormalizer.normalizeList(r.refusedTypes())));
        entity.setArrivalTime(r.arrivalTime());
        entity.setCurrency(r.currency() == null || r.currency().isBlank()
                ? null : r.currency().trim().toUpperCase(Locale.ROOT));
        entity.setPricingMode(pricingMode);
        entity.setAcceptedPaymentMethods(joinMethods(methods));
        entity.setCashAccepted(methods.contains(PaymentMethod.CASH));
        entity.setNegotiable(Boolean.TRUE.equals(r.negotiable()));
        entity.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        entity.setPickupAddressLabel(r.pickupAddress() == null ? null : r.pickupAddress().label());
        entity.setPickupLat(r.pickupAddress() == null ? null : r.pickupAddress().lat());
        entity.setPickupLng(r.pickupAddress() == null ? null : r.pickupAddress().lng());
        entity.setDeliveryAddressLabel(r.deliveryAddress() == null ? null : r.deliveryAddress().label());
        entity.setDeliveryLat(r.deliveryAddress() == null ? null : r.deliveryAddress().lat());
        entity.setDeliveryLng(r.deliveryAddress() == null ? null : r.deliveryAddress().lng());
        entity.setDepartureTime(r.departureTime());
        entity.setHandoverLeadDays(r.handoverLeadDays());
        entity.setDepartureCountryCode(upperOrNull(r.departureCountryCode()));
        entity.setArrivalCountryCode(upperOrNull(r.arrivalCountryCode()));
    }

    /** Devise du modèle si fournie (422 sinon inconnue), sinon devise active du profil. */
    private SupportedCurrency resolveCurrency(UUID userId, String requested) {
        if (requested == null || requested.isBlank()) {
            return SupportedCurrency.fromCodeOrDefault(activeCurrencyResolver.resolve(userId));
        }
        SupportedCurrency currency = SupportedCurrency.fromCode(requested);
        if (currency == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "currency-unsupported", "Currency Unsupported",
                    "Cette devise n'est pas prise en charge par yadony.");
        }
        return currency;
    }

    /**
     * Moyens de paiement du modèle. Absents (client antérieur au formulaire complet) :
     * dérivés de cashAccepted, comme avant. Seuls STRIPE, CASH et MOBILE_MONEY sont
     * acceptés (les valeurs legacy WAVE / ORANGE_MONEY ne sont plus proposées), et
     * chaque moyen doit être permis par la devise (la carte hors zone CFA, le mobile
     * money dedans), sinon le modèle produirait un trajet que le serveur refuserait.
     */
    private Set<PaymentMethod> resolvePaymentMethods(Set<PaymentMethod> requested, boolean cashAccepted,
                                                     SupportedCurrency currency) {
        if (requested == null) {
            return cashAccepted
                    ? EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH)
                    : EnumSet.of(PaymentMethod.STRIPE);
        }
        EnumSet<PaymentMethod> methods = requested.isEmpty()
                ? EnumSet.of(PaymentMethod.STRIPE) : EnumSet.copyOf(requested);
        for (PaymentMethod method : methods) {
            if (!ALLOWED_METHODS.contains(method)) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "trip-template/payment-method-invalid", "Payment Method Invalid",
                        "Ce moyen de paiement n'est plus proposé.");
            }
            if (!CurrencyPaymentRails.allows(currency, method)) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "trip-template/payment-method-not-available", "Payment Method Not Available",
                        "Ce moyen de paiement n'est pas disponible dans cette devise.");
            }
        }
        return methods;
    }

    /**
     * En mode KG le prix est obligatoire ; en mode MIXED (grille de profil) il est
     * facultatif. Le plafond suit la devise du modèle : figé à 500 il valait 0,76 €/kg
     * en franc CFA et aucun modèle XOF réaliste ne passait.
     */
    private void assertPricePerKg(Double pricePerKg, String pricingMode, SupportedCurrency currency) {
        if (pricePerKg == null || pricePerKg <= 0) {
            if ("KG".equals(pricingMode)) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "trip-template/price-required", "Price Required",
                        "Un prix au kilo est requis en tarification au kilo.");
            }
            return;
        }
        if (BigDecimal.valueOf(pricePerKg).compareTo(CurrencyBounds.maxPricePerKg(currency)) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "trip-template/price-out-of-bounds", "Price Out Of Bounds",
                    "Ce prix au kilo dépasse le plafond autorisé dans cette devise.");
        }
    }

    /** Une adresse est mémorisée entière (libellé + coordonnées) ou pas du tout. */
    private void assertAddressCompleteOrAbsent(AddressDto address) {
        if (address == null) {
            return;
        }
        boolean complete = address.label() != null && !address.label().isBlank()
                && address.lat() != null && address.lng() != null;
        if (!complete) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "trip-template/address-incomplete", "Address Incomplete",
                    "Une adresse doit comporter un libellé et des coordonnées.");
        }
    }

    private static String upperOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String joinMethods(Set<PaymentMethod> methods) {
        return methods.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static Set<PaymentMethod> splitMethods(String joined) {
        if (joined == null || joined.isBlank()) {
            return EnumSet.of(PaymentMethod.STRIPE);
        }
        EnumSet<PaymentMethod> methods = EnumSet.noneOf(PaymentMethod.class);
        for (String token : joined.split(",")) {
            String name = token.trim();
            if (!name.isEmpty()) {
                methods.add(PaymentMethod.valueOf(name));
            }
        }
        return methods;
    }

    private static AddressDto addressOrNull(String label, Double lat, Double lng) {
        return label == null ? null : new AddressDto(label, lat, lng);
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
                e.getCurrency(), e.getPricingMode(), splitMethods(e.getAcceptedPaymentMethods()),
                e.isNegotiable(), splitCategories(e.getRefusedTypes()), e.getDescription(),
                addressOrNull(e.getPickupAddressLabel(), e.getPickupLat(), e.getPickupLng()),
                addressOrNull(e.getDeliveryAddressLabel(), e.getDeliveryLat(), e.getDeliveryLng()),
                e.getDepartureTime(), e.getHandoverLeadDays(),
                e.getDepartureCountryCode(), e.getArrivalCountryCode(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
