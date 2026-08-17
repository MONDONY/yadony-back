package com.yadony.api.matching;

import com.yadony.api.common.BaseEntity;
import com.yadony.api.matching.PricingMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.cash.PaymentMethodSetConverter;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Convert;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "announcements")
@Where(clause = "deleted_at IS NULL")
public class AnnouncementEntity extends BaseEntity {

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "departure_city", nullable = false, length = 100)
    private String departureCity;

    @Column(name = "arrival_city", nullable = false, length = 100)
    private String arrivalCity;

    @Column(name = "departure_country_code", length = 2)
    private String departureCountryCode;

    @Column(name = "arrival_country_code", length = 2)
    private String arrivalCountryCode;

    @Column(name = "departure_date", nullable = false)
    private LocalDate departureDate;

    @Column(name = "departure_time")
    private LocalTime departureTime;

    // Instant canonique de départ (date + heure, fuseau ville de départ).
    // Référence du verrou d'annulation après remise (D1/D3). Nullable au niveau
    // schéma (best-effort) ; l'obligation est portée par @NotNull sur la requête.
    @Column(name = "departure_at")
    private OffsetDateTime departureAt;

    @Column(name = "arrival_time")
    private LocalTime arrivalTime;

    @Column(name = "handover_deadline")
    private LocalDateTime handoverDeadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", nullable = false, length = 20)
    private TransportMode transportMode;

    @Column(name = "pickup_address_label", nullable = false, length = 500)
    private String pickupAddressLabel;

    @Column(name = "pickup_lat", nullable = false, precision = 9, scale = 6)
    private java.math.BigDecimal pickupLat;

    @Column(name = "pickup_lng", nullable = false, precision = 9, scale = 6)
    private java.math.BigDecimal pickupLng;

    @Column(name = "delivery_address_label", nullable = false, length = 500)
    private String deliveryAddressLabel;

    @Column(name = "delivery_lat", nullable = false, precision = 9, scale = 6)
    private java.math.BigDecimal deliveryLat;

    @Column(name = "delivery_lng", nullable = false, precision = 9, scale = 6)
    private java.math.BigDecimal deliveryLng;

    @Column(name = "available_kg", nullable = false, precision = 5, scale = 2)
    private BigDecimal availableKg;

    @Column(name = "total_kg", nullable = false, precision = 5, scale = 2)
    private BigDecimal totalKg;

    @Column(name = "price_per_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerKg;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AnnouncementStatus status = AnnouncementStatus.ACTIVE;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "arrival_instructions", columnDefinition = "TEXT")
    private String arrivalInstructions;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "announcement_accepted_types",
            joinColumns = @JoinColumn(name = "announcement_id")
    )
    @Column(name = "content_type", length = 100)
    private List<String> acceptedContentTypes = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "announcement_refused_types",
            joinColumns = @JoinColumn(name = "announcement_id")
    )
    @Column(name = "content_type", length = 100)
    private List<String> refusedTypes = new ArrayList<>();

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone = "Europe/Paris";

    @Column(name = "total_trips_counted", nullable = false)
    private boolean totalTripsCounted = false;

    @Column(name = "traveler_is_pro", nullable = false)
    private boolean travelerIsPro = false;

    /**
     * If non-null, this announcement is a "dedicated trip" — created by the
     * traveler in response to a sender's package_request via
     * POST /negotiations/{id}/create-dedicated-trip. It must NOT appear in
     * the public announcements search, only in the negotiating sender's view.
     */
    @Column(name = "linked_package_request_id")
    private UUID linkedPackageRequestId;

    /**
     * Surplus capacity (capacité excédentaire) — only meaningful for dedicated trips.
     * reservedKg: part réservée à la négociation (immuable), 0 pour les trajets normaux.
     * surplusEligible: la négociation est payée → le voyageur peut ouvrir le surplus.
     * surplusPublished: le surplus est ouvert et visible dans la recherche publique.
     */
    @Column(name = "reserved_kg", nullable = false, precision = 5, scale = 2)
    private BigDecimal reservedKg = BigDecimal.ZERO;

    @Column(name = "surplus_eligible", nullable = false)
    private boolean surplusEligible = false;

    @Column(name = "surplus_published", nullable = false)
    private boolean surplusPublished = false;

    /**
     * Nombre de consultations de la page publique de partage (l'affiche générée
     * dans l'app et postée par le voyageur sur ses propres canaux).
     *
     * Nullable à dessein : une colonne NOT NULL ajoutée en V213 casserait les
     * tests de migration sur H2, dont le DDL est dérivé de JPA sans reprendre
     * le DEFAULT déclaré côté Flyway. Toutes les lectures passent par COALESCE.
     */
    @Column(name = "share_view_count")
    private Long shareViewCount = 0L;

    /**
     * Sender « réservé » d'un trajet dédié : l'expéditeur de la négociation pour
     * qui ce trajet a été créé. NULL pour les trajets non dédiés. Sert à empêcher
     * ce sender de re-bidder sur le surplus de son propre trajet (il a déjà son
     * colis réservé dessus).
     */
    @Column(name = "reserved_sender_id")
    private UUID reservedSenderId;

    @Convert(converter = PaymentMethodSetConverter.class)
    @Column(name = "accepted_payment_methods", nullable = false)
    private Set<PaymentMethod> acceptedPaymentMethods = EnumSet.of(PaymentMethod.STRIPE);

    @Enumerated(EnumType.STRING)
    @Column(name = "capacity_unit", nullable = false, length = 20)
    private CapacityUnit capacityUnit = CapacityUnit.SUITCASE_23KG;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_mode", nullable = false, length = 10)
    private PricingMode pricingMode = PricingMode.KG;

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public boolean isTotalTripsCounted() { return totalTripsCounted; }
    public void setTotalTripsCounted(boolean totalTripsCounted) { this.totalTripsCounted = totalTripsCounted; }

    public boolean isTravelerIsPro() { return travelerIsPro; }
    public void setTravelerIsPro(boolean travelerIsPro) { this.travelerIsPro = travelerIsPro; }

    public UUID getLinkedPackageRequestId() { return linkedPackageRequestId; }
    public void setLinkedPackageRequestId(UUID linkedPackageRequestId) { this.linkedPackageRequestId = linkedPackageRequestId; }

    public BigDecimal getReservedKg() { return reservedKg; }
    public void setReservedKg(BigDecimal reservedKg) { this.reservedKg = reservedKg; }
    public boolean isSurplusEligible() { return surplusEligible; }
    public void setSurplusEligible(boolean surplusEligible) { this.surplusEligible = surplusEligible; }
    public boolean isSurplusPublished() { return surplusPublished; }
    public void setSurplusPublished(boolean surplusPublished) { this.surplusPublished = surplusPublished; }

    /**
     * Jamais null pour l'appelant : les lignes antérieures à V213 valent NULL en base.
     *
     * <p>Pas de setter : la colonne n'est écrite que par
     * {@code AnnouncementRepository.incrementShareViewCount}, qui incrémente sans
     * charger l'entité. Un setter n'aurait aucun appelant.
     */
    public long getShareViewCount() { return shareViewCount == null ? 0L : shareViewCount; }

    /**
     * A dedicated trip (linkedPackageRequestId != null) is tied to a private
     * negotiation: its capacity is reserved for the negotiating sender. Until the
     * traveler explicitly opens the surplus capacity (surplusPublished == true,
     * after the negotiating sender has paid), NO third-party sender may bid on it.
     * <p>
     * This guard MUST be checked in every public bid-creation entry point
     * (cash bids via {@code BidService.createBid}, Stripe checkout via
     * {@code BidCheckoutService.checkout}) so a third party cannot drive an escrow
     * against the reserved capacity of a private negotiation. Centralised here so a
     * future entry point cannot drift from the rule. Read-only, no extra dependencies.
     */
    public boolean isClosedToThirdPartyBids() {
        return linkedPackageRequestId != null && !surplusPublished;
    }

    /**
     * L'annonce peut-elle être montrée à un tiers non authentifié.
     *
     * <p>Pendant en mémoire de {@code AnnouncementSpecification.hasStatus(ACTIVE)
     * .and(publicOrOpenSurplus())}, la règle du feed de recherche : mêmes deux
     * conditions, exprimées ici pour les surfaces qui tiennent déjà l'entité et
     * n'ont donc pas de Specification à composer — au premier chef la page
     * publique de partage d'une affiche.
     *
     * <p>Centralisé pour la même raison que {@link #isClosedToThirdPartyBids()} :
     * une surface publique qui redécide « status == ACTIVE » de son côté finit
     * par exposer un trajet dédié, dont la capacité est réservée à une
     * négociation privée. {@code FULL} est exclu à dessein — sans place
     * restante, présenter le trajet comme réservable envoie du trafic vers un
     * appel à l'action que le backend refusera.
     */
    public boolean isPubliclyListable() {
        return status == AnnouncementStatus.ACTIVE && !isClosedToThirdPartyBids();
    }

    public UUID getReservedSenderId() { return reservedSenderId; }
    public void setReservedSenderId(UUID reservedSenderId) { this.reservedSenderId = reservedSenderId; }

    /**
     * True if {@code senderId} is the negotiating sender for whom this dedicated
     * trip was created. That sender already holds the reserved capacity (their
     * negotiated parcel), so they must NOT be able to place an additional bid on
     * the same trip's surplus — they would end up with two shipments on one trip.
     * <p>
     * Checked in every public bid-creation entry point ({@code BidService.createBid},
     * {@code BidCheckoutService.checkout}). Deterministic: it does not depend on the
     * negotiation bid having been materialised, so it holds even if that bid is
     * missing or delayed. Read-only, no extra dependencies.
     */
    public boolean isReservedSender(UUID senderId) {
        return reservedSenderId != null && reservedSenderId.equals(senderId);
    }

    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }

    public String getDepartureCity() { return departureCity; }
    public void setDepartureCity(String departureCity) { this.departureCity = departureCity; }

    public String getArrivalCity() { return arrivalCity; }
    public void setArrivalCity(String arrivalCity) { this.arrivalCity = arrivalCity; }

    public String getDepartureCountryCode() { return departureCountryCode; }
    public void setDepartureCountryCode(String departureCountryCode) { this.departureCountryCode = departureCountryCode; }

    public String getArrivalCountryCode() { return arrivalCountryCode; }
    public void setArrivalCountryCode(String arrivalCountryCode) { this.arrivalCountryCode = arrivalCountryCode; }

    public LocalDate getDepartureDate() { return departureDate; }
    public void setDepartureDate(LocalDate departureDate) { this.departureDate = departureDate; }

    public LocalTime getDepartureTime() { return departureTime; }
    public void setDepartureTime(LocalTime departureTime) { this.departureTime = departureTime; }

    public OffsetDateTime getDepartureAt() { return departureAt; }
    public void setDepartureAt(OffsetDateTime departureAt) { this.departureAt = departureAt; }

    public LocalTime getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(LocalTime arrivalTime) { this.arrivalTime = arrivalTime; }

    public TransportMode getTransportMode() { return transportMode; }
    public void setTransportMode(TransportMode transportMode) { this.transportMode = transportMode; }

    public String getPickupAddressLabel() { return pickupAddressLabel; }
    public void setPickupAddressLabel(String v) { this.pickupAddressLabel = v; }
    public java.math.BigDecimal getPickupLat() { return pickupLat; }
    public void setPickupLat(java.math.BigDecimal v) { this.pickupLat = v; }
    public java.math.BigDecimal getPickupLng() { return pickupLng; }
    public void setPickupLng(java.math.BigDecimal v) { this.pickupLng = v; }
    public String getDeliveryAddressLabel() { return deliveryAddressLabel; }
    public void setDeliveryAddressLabel(String v) { this.deliveryAddressLabel = v; }
    public java.math.BigDecimal getDeliveryLat() { return deliveryLat; }
    public void setDeliveryLat(java.math.BigDecimal v) { this.deliveryLat = v; }
    public java.math.BigDecimal getDeliveryLng() { return deliveryLng; }
    public void setDeliveryLng(java.math.BigDecimal v) { this.deliveryLng = v; }

    public BigDecimal getAvailableKg() { return availableKg; }
    public void setAvailableKg(BigDecimal availableKg) { this.availableKg = availableKg; }

    public BigDecimal getTotalKg() { return totalKg; }
    public void setTotalKg(BigDecimal totalKg) { this.totalKg = totalKg; }

    public BigDecimal getPricePerKg() { return pricePerKg; }
    public void setPricePerKg(BigDecimal pricePerKg) { this.pricePerKg = pricePerKg; }

    public AnnouncementStatus getStatus() { return status; }
    public void setStatus(AnnouncementStatus status) { this.status = status; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getArrivalInstructions() { return arrivalInstructions; }
    public void setArrivalInstructions(String arrivalInstructions) { this.arrivalInstructions = arrivalInstructions; }

    public List<String> getAcceptedContentTypes() { return acceptedContentTypes; }
    public void setAcceptedContentTypes(List<String> acceptedContentTypes) { this.acceptedContentTypes = acceptedContentTypes; }

    public List<String> getRefusedTypes() { return refusedTypes; }
    public void setRefusedTypes(List<String> refusedTypes) { this.refusedTypes = refusedTypes; }

    public Set<PaymentMethod> getAcceptedPaymentMethods() { return acceptedPaymentMethods; }
    public void setAcceptedPaymentMethods(Set<PaymentMethod> acceptedPaymentMethods) { this.acceptedPaymentMethods = acceptedPaymentMethods; }

    public CapacityUnit getCapacityUnit() { return capacityUnit; }
    public void setCapacityUnit(CapacityUnit capacityUnit) { this.capacityUnit = capacityUnit; }

    public PricingMode getPricingMode() { return pricingMode; }
    public void setPricingMode(PricingMode pricingMode) { this.pricingMode = pricingMode; }

    public LocalDateTime getHandoverDeadline() { return handoverDeadline; }
    public void setHandoverDeadline(LocalDateTime handoverDeadline) { this.handoverDeadline = handoverDeadline; }
}
