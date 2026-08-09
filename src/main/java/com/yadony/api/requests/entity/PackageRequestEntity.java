package com.yadony.api.requests.entity;

import com.yadony.api.common.BaseEntity;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.cash.PaymentMethodSetConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "package_requests")
@SQLRestriction("deleted_at IS NULL")
public class PackageRequestEntity extends BaseEntity {

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "departure_city", nullable = false, length = 100)
    private String departureCity;

    @Column(name = "arrival_city", nullable = false, length = 100)
    private String arrivalCity;

    @Column(name = "desired_date", nullable = false)
    private LocalDate desiredDate;

    @Column(name = "date_tolerance_days", nullable = false)
    private Short dateToleranceDays;

    @Column(name = "weight_kg", nullable = false, precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Enumerated(EnumType.STRING)
    @Column(name = "parcel_size", nullable = false, length = 10)
    private ParcelSize parcelSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", nullable = false, length = 20)
    private com.yadony.api.matching.TransportMode transportMode;

    // Colonne élargie en TEXT par V171 (multi-sélection canonique jointe par virgule
    // peut dépasser 255) — columnDefinition reflète le vrai type DB (length est
    // trompeur : Hibernate ne le valide pas au runtime, mais un DDL futur s'y fierait).
    @Column(name = "content_category", nullable = false, columnDefinition = "TEXT")
    private String contentCategory;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "target_price_eur", precision = 10, scale = 2)
    private BigDecimal targetPriceEur;

    /**
     * Code promo saisi par l'expéditeur à la création/publication (brut,
     * non validé ici — validation paresseuse au moment du paiement réel,
     * même contrat que {@code BidEntity.promoCode}). Copié sur le thread de
     * négociation dès sa création ({@code NegotiationService.start}) pour
     * être appliqué automatiquement au paiement, sans re-saisie.
     */
    @Column(name = "promo_code", length = 50)
    private String promoCode;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "pickup_neighborhood", length = 100)
    private String pickupNeighborhood;

    @Column(name = "delivery_neighborhood", length = 100)
    private String deliveryNeighborhood;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PackageRequestStatus status;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "negotiable", nullable = false)
    private boolean negotiable = true;

    @Convert(converter = PaymentMethodSetConverter.class)
    @Column(name = "accepted_payment_methods", nullable = false)
    private Set<PaymentMethod> acceptedPaymentMethods = EnumSet.of(PaymentMethod.STRIPE);

    // Post-acceptation fields (nullable until accepted)
    @Column(name = "pickup_address_label", length = 255)
    private String pickupAddressLabel;

    @Column(name = "pickup_lat", precision = 9, scale = 6)
    private BigDecimal pickupLat;

    @Column(name = "pickup_lng", precision = 9, scale = 6)
    private BigDecimal pickupLng;

    @Column(name = "delivery_address_label", length = 255)
    private String deliveryAddressLabel;

    @Column(name = "delivery_lat", precision = 9, scale = 6)
    private BigDecimal deliveryLat;

    @Column(name = "delivery_lng", precision = 9, scale = 6)
    private BigDecimal deliveryLng;

    @Column(name = "recipient_name", length = 100)
    private String recipientName;

    @Column(name = "recipient_phone", length = 30)
    private String recipientPhone;

    @Column(name = "recipient_city", length = 100)
    private String recipientCity;

    @Column(name = "disclaimer_signed_at")
    private LocalDateTime disclaimerSignedAt;

    @Column(name = "disclaimer_signed_ip", length = 45)
    private String disclaimerSignedIp;

    // === NO-ARG CONSTRUCTOR (required by JPA) ===

    public PackageRequestEntity() { /* JPA */ }

    // === GETTERS ===

    public UUID getSenderId() { return senderId; }

    public String getDepartureCity() { return departureCity; }

    public String getArrivalCity() { return arrivalCity; }

    public LocalDate getDesiredDate() { return desiredDate; }

    public Short getDateToleranceDays() { return dateToleranceDays; }

    public BigDecimal getWeightKg() { return weightKg; }

    public ParcelSize getParcelSize() { return parcelSize; }

    public com.yadony.api.matching.TransportMode getTransportMode() { return transportMode; }

    public String getContentCategory() { return contentCategory; }

    public String getDescription() { return description; }

    public BigDecimal getTargetPriceEur() { return targetPriceEur; }
    public String getPromoCode() { return promoCode; }

    public String getPhotoUrl() { return photoUrl; }

    public String getPickupNeighborhood() { return pickupNeighborhood; }

    public String getDeliveryNeighborhood() { return deliveryNeighborhood; }

    public PackageRequestStatus getStatus() { return status; }

    public String getCurrency() { return currency; }

    public boolean isNegotiable() { return negotiable; }

    public Set<PaymentMethod> getAcceptedPaymentMethods() { return acceptedPaymentMethods; }

    public String getPickupAddressLabel() { return pickupAddressLabel; }

    public BigDecimal getPickupLat() { return pickupLat; }

    public BigDecimal getPickupLng() { return pickupLng; }

    public String getDeliveryAddressLabel() { return deliveryAddressLabel; }

    public BigDecimal getDeliveryLat() { return deliveryLat; }

    public BigDecimal getDeliveryLng() { return deliveryLng; }

    public String getRecipientName() { return recipientName; }

    public String getRecipientPhone() { return recipientPhone; }

    public String getRecipientCity() { return recipientCity; }

    public LocalDateTime getDisclaimerSignedAt() { return disclaimerSignedAt; }

    public String getDisclaimerSignedIp() { return disclaimerSignedIp; }

    // === SETTERS ===

    public void setSenderId(UUID senderId) { this.senderId = senderId; }

    public void setDepartureCity(String departureCity) { this.departureCity = departureCity; }

    public void setArrivalCity(String arrivalCity) { this.arrivalCity = arrivalCity; }

    public void setDesiredDate(LocalDate desiredDate) { this.desiredDate = desiredDate; }

    public void setDateToleranceDays(Short dateToleranceDays) { this.dateToleranceDays = dateToleranceDays; }

    public void setWeightKg(BigDecimal weightKg) { this.weightKg = weightKg; }

    public void setParcelSize(ParcelSize parcelSize) { this.parcelSize = parcelSize; }

    public void setTransportMode(com.yadony.api.matching.TransportMode transportMode) { this.transportMode = transportMode; }

    public void setContentCategory(String contentCategory) { this.contentCategory = contentCategory; }

    public void setDescription(String description) { this.description = description; }

    public void setTargetPriceEur(BigDecimal targetPriceEur) { this.targetPriceEur = targetPriceEur; }
    public void setPromoCode(String promoCode) { this.promoCode = promoCode; }

    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public void setPickupNeighborhood(String pickupNeighborhood) { this.pickupNeighborhood = pickupNeighborhood; }

    public void setDeliveryNeighborhood(String deliveryNeighborhood) { this.deliveryNeighborhood = deliveryNeighborhood; }

    public void setStatus(PackageRequestStatus status) { this.status = status; }

    public void setCurrency(String currency) { this.currency = currency; }

    public void setNegotiable(boolean negotiable) { this.negotiable = negotiable; }

    public void setAcceptedPaymentMethods(Set<PaymentMethod> acceptedPaymentMethods) { this.acceptedPaymentMethods = acceptedPaymentMethods; }

    public void setPickupAddressLabel(String pickupAddressLabel) { this.pickupAddressLabel = pickupAddressLabel; }

    public void setPickupLat(BigDecimal pickupLat) { this.pickupLat = pickupLat; }

    public void setPickupLng(BigDecimal pickupLng) { this.pickupLng = pickupLng; }

    public void setDeliveryAddressLabel(String deliveryAddressLabel) { this.deliveryAddressLabel = deliveryAddressLabel; }

    public void setDeliveryLat(BigDecimal deliveryLat) { this.deliveryLat = deliveryLat; }

    public void setDeliveryLng(BigDecimal deliveryLng) { this.deliveryLng = deliveryLng; }

    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }

    public void setRecipientPhone(String recipientPhone) { this.recipientPhone = recipientPhone; }

    public void setRecipientCity(String recipientCity) { this.recipientCity = recipientCity; }

    public void setDisclaimerSignedAt(LocalDateTime disclaimerSignedAt) { this.disclaimerSignedAt = disclaimerSignedAt; }

    public void setDisclaimerSignedIp(String disclaimerSignedIp) { this.disclaimerSignedIp = disclaimerSignedIp; }
}
