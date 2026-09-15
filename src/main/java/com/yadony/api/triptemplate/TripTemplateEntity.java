package com.yadony.api.triptemplate;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "trip_templates")
@Where(clause = "deleted_at IS NULL")
public class TripTemplateEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "label", nullable = false, length = 60)
    private String label;

    @Column(name = "emoji", length = 8)
    private String emoji;

    @Column(name = "departure_city", nullable = false, length = 100)
    private String departureCity;

    @Column(name = "departure_lat")
    private Double departureLat;

    @Column(name = "departure_lng")
    private Double departureLng;

    @Column(name = "arrival_city", nullable = false, length = 100)
    private String arrivalCity;

    @Column(name = "arrival_lat")
    private Double arrivalLat;

    @Column(name = "arrival_lng")
    private Double arrivalLng;

    @Column(name = "transport_mode", nullable = false, length = 20)
    private String transportMode = "PLANE";

    @Column(name = "capacity_unit", nullable = false, length = 20)
    private String capacityUnit = "SUITCASE_23KG";

    @Column(name = "available_kg", nullable = false)
    private Integer availableKg = 23;

    @Column(name = "price_per_kg")
    private Double pricePerKg;

    @Column(name = "accepted_categories", columnDefinition = "TEXT")
    private String acceptedCategories;

    @Column(name = "cash_accepted", nullable = false)
    private boolean cashAccepted = false;

    @Column(name = "arrival_time")
    private LocalTime arrivalTime;

    /** Devise du modèle (ISO 4217). Nul pour un modèle antérieur à V257 : devise active à l'application. */
    @Column(name = "currency", length = 3)
    private String currency;

    /** "KG" ou "MIXED" (grille de profil + kilo optionnel). Chaîne comme transportMode et capacityUnit. */
    @Column(name = "pricing_mode", nullable = false, length = 10)
    private String pricingMode = "KG";

    /** Codes PaymentMethod joints par virgule, comme accepted_categories. cash_accepted en est le miroir. */
    @Column(name = "accepted_payment_methods", nullable = false, columnDefinition = "TEXT")
    private String acceptedPaymentMethods = "STRIPE";

    @Column(name = "negotiable", nullable = false)
    private boolean negotiable = false;

    @Column(name = "refused_types", columnDefinition = "TEXT")
    private String refusedTypes;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "pickup_address_label", length = 500)
    private String pickupAddressLabel;

    @Column(name = "pickup_lat", precision = 9, scale = 6)
    private BigDecimal pickupLat;

    @Column(name = "pickup_lng", precision = 9, scale = 6)
    private BigDecimal pickupLng;

    @Column(name = "delivery_address_label", length = 500)
    private String deliveryAddressLabel;

    @Column(name = "delivery_lat", precision = 9, scale = 6)
    private BigDecimal deliveryLat;

    @Column(name = "delivery_lng", precision = 9, scale = 6)
    private BigDecimal deliveryLng;

    @Column(name = "departure_time")
    private LocalTime departureTime;

    /** Remise au plus tard N jours avant le départ (0 = le jour du départ). Nul : pas de délai mémorisé. */
    @Column(name = "handover_lead_days")
    private Integer handoverLeadDays;

    @Column(name = "departure_country_code", length = 2)
    private String departureCountryCode;

    @Column(name = "arrival_country_code", length = 2)
    private String arrivalCountryCode;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }
    public String getDepartureCity() { return departureCity; }
    public void setDepartureCity(String departureCity) { this.departureCity = departureCity; }
    public Double getDepartureLat() { return departureLat; }
    public void setDepartureLat(Double departureLat) { this.departureLat = departureLat; }
    public Double getDepartureLng() { return departureLng; }
    public void setDepartureLng(Double departureLng) { this.departureLng = departureLng; }
    public String getArrivalCity() { return arrivalCity; }
    public void setArrivalCity(String arrivalCity) { this.arrivalCity = arrivalCity; }
    public Double getArrivalLat() { return arrivalLat; }
    public void setArrivalLat(Double arrivalLat) { this.arrivalLat = arrivalLat; }
    public Double getArrivalLng() { return arrivalLng; }
    public void setArrivalLng(Double arrivalLng) { this.arrivalLng = arrivalLng; }
    public String getTransportMode() { return transportMode; }
    public void setTransportMode(String transportMode) { this.transportMode = transportMode; }
    public String getCapacityUnit() { return capacityUnit; }
    public void setCapacityUnit(String capacityUnit) { this.capacityUnit = capacityUnit; }
    public Integer getAvailableKg() { return availableKg; }
    public void setAvailableKg(Integer availableKg) { this.availableKg = availableKg; }
    public Double getPricePerKg() { return pricePerKg; }
    public void setPricePerKg(Double pricePerKg) { this.pricePerKg = pricePerKg; }
    public String getAcceptedCategories() { return acceptedCategories; }
    public void setAcceptedCategories(String acceptedCategories) { this.acceptedCategories = acceptedCategories; }
    public boolean isCashAccepted() { return cashAccepted; }
    public void setCashAccepted(boolean cashAccepted) { this.cashAccepted = cashAccepted; }
    public LocalTime getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(LocalTime arrivalTime) { this.arrivalTime = arrivalTime; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getPricingMode() { return pricingMode; }
    public void setPricingMode(String pricingMode) { this.pricingMode = pricingMode; }
    public String getAcceptedPaymentMethods() { return acceptedPaymentMethods; }
    public void setAcceptedPaymentMethods(String acceptedPaymentMethods) { this.acceptedPaymentMethods = acceptedPaymentMethods; }
    public boolean isNegotiable() { return negotiable; }
    public void setNegotiable(boolean negotiable) { this.negotiable = negotiable; }
    public String getRefusedTypes() { return refusedTypes; }
    public void setRefusedTypes(String refusedTypes) { this.refusedTypes = refusedTypes; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPickupAddressLabel() { return pickupAddressLabel; }
    public void setPickupAddressLabel(String pickupAddressLabel) { this.pickupAddressLabel = pickupAddressLabel; }
    public BigDecimal getPickupLat() { return pickupLat; }
    public void setPickupLat(BigDecimal pickupLat) { this.pickupLat = pickupLat; }
    public BigDecimal getPickupLng() { return pickupLng; }
    public void setPickupLng(BigDecimal pickupLng) { this.pickupLng = pickupLng; }
    public String getDeliveryAddressLabel() { return deliveryAddressLabel; }
    public void setDeliveryAddressLabel(String deliveryAddressLabel) { this.deliveryAddressLabel = deliveryAddressLabel; }
    public BigDecimal getDeliveryLat() { return deliveryLat; }
    public void setDeliveryLat(BigDecimal deliveryLat) { this.deliveryLat = deliveryLat; }
    public BigDecimal getDeliveryLng() { return deliveryLng; }
    public void setDeliveryLng(BigDecimal deliveryLng) { this.deliveryLng = deliveryLng; }
    public LocalTime getDepartureTime() { return departureTime; }
    public void setDepartureTime(LocalTime departureTime) { this.departureTime = departureTime; }
    public Integer getHandoverLeadDays() { return handoverLeadDays; }
    public void setHandoverLeadDays(Integer handoverLeadDays) { this.handoverLeadDays = handoverLeadDays; }
    public String getDepartureCountryCode() { return departureCountryCode; }
    public void setDepartureCountryCode(String departureCountryCode) { this.departureCountryCode = departureCountryCode; }
    public String getArrivalCountryCode() { return arrivalCountryCode; }
    public void setArrivalCountryCode(String arrivalCountryCode) { this.arrivalCountryCode = arrivalCountryCode; }
}
