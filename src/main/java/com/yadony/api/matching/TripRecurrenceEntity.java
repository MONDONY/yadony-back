package com.yadony.api.matching;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "trip_recurrences")
@Where(clause = "deleted_at IS NULL")
public class TripRecurrenceEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "source_template_id")
    private UUID sourceTemplateId;

    @Column(name = "departure_city", nullable = false, length = 100)
    private String departureCity;

    @Column(name = "arrival_city", nullable = false, length = 100)
    private String arrivalCity;

    @Column(name = "transport_mode", nullable = false, length = 20)
    private String transportMode = "PLANE";

    @Column(name = "capacity_unit", nullable = false, length = 20)
    private String capacityUnit = "SUITCASE_23KG";

    @Column(name = "available_kg", nullable = false)
    private Double availableKg;

    @Column(name = "price_per_kg", nullable = false)
    private Double pricePerKg;

    @Column(name = "accepted_categories", columnDefinition = "TEXT")
    private String acceptedCategories;

    @Column(name = "pickup_label", nullable = false, length = 255)
    private String pickupLabel;

    @Column(name = "pickup_lat", nullable = false)
    private Double pickupLat;

    @Column(name = "pickup_lng", nullable = false)
    private Double pickupLng;

    @Column(name = "delivery_label", nullable = false, length = 255)
    private String deliveryLabel;

    @Column(name = "delivery_lat", nullable = false)
    private Double deliveryLat;

    @Column(name = "delivery_lng", nullable = false)
    private Double deliveryLng;

    @Column(name = "departure_time")
    private LocalTime departureTime;

    @Column(name = "arrival_time")
    private LocalTime arrivalTime;

    @Column(name = "cash_accepted", nullable = false)
    private boolean cashAccepted = false;

    @Column(name = "weekdays", nullable = false, length = 7)
    private String weekdays;

    @Column(name = "horizon_days", nullable = false)
    private Integer horizonDays = 14;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_generated_date")
    private LocalDate lastGeneratedDate;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "week_interval", nullable = false)
    private Integer weekInterval = 1;

    @Column(name = "publication_lead_days", nullable = false)
    private Integer publicationLeadDays = 14;

    @Column(name = "handover_lead_days", nullable = false)
    private Integer handoverLeadDays = 0;

    @Column(name = "pricing_mode", nullable = false, length = 10)
    private PricingMode pricingMode = PricingMode.KG;

    @Column(name = "negotiable", nullable = false)
    private boolean negotiable = false;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "refused_categories", columnDefinition = "TEXT")
    private String refusedCategories;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "last_publication_error_code", length = 100)
    private String lastPublicationErrorCode;

    @Column(name = "last_publication_error_message", length = 255)
    private String lastPublicationErrorMessage;

    @Column(name = "last_publication_error_at")
    private LocalDateTime lastPublicationErrorAt;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getSourceTemplateId() { return sourceTemplateId; }
    public void setSourceTemplateId(UUID sourceTemplateId) { this.sourceTemplateId = sourceTemplateId; }
    public String getDepartureCity() { return departureCity; }
    public void setDepartureCity(String departureCity) { this.departureCity = departureCity; }
    public String getArrivalCity() { return arrivalCity; }
    public void setArrivalCity(String arrivalCity) { this.arrivalCity = arrivalCity; }
    public String getTransportMode() { return transportMode; }
    public void setTransportMode(String transportMode) { this.transportMode = transportMode; }
    public String getCapacityUnit() { return capacityUnit; }
    public void setCapacityUnit(String capacityUnit) { this.capacityUnit = capacityUnit; }
    public Double getAvailableKg() { return availableKg; }
    public void setAvailableKg(Double availableKg) { this.availableKg = availableKg; }
    public Double getPricePerKg() { return pricePerKg; }
    public void setPricePerKg(Double pricePerKg) { this.pricePerKg = pricePerKg; }
    public String getAcceptedCategories() { return acceptedCategories; }
    public void setAcceptedCategories(String acceptedCategories) { this.acceptedCategories = acceptedCategories; }
    public String getPickupLabel() { return pickupLabel; }
    public void setPickupLabel(String pickupLabel) { this.pickupLabel = pickupLabel; }
    public Double getPickupLat() { return pickupLat; }
    public void setPickupLat(Double pickupLat) { this.pickupLat = pickupLat; }
    public Double getPickupLng() { return pickupLng; }
    public void setPickupLng(Double pickupLng) { this.pickupLng = pickupLng; }
    public String getDeliveryLabel() { return deliveryLabel; }
    public void setDeliveryLabel(String deliveryLabel) { this.deliveryLabel = deliveryLabel; }
    public Double getDeliveryLat() { return deliveryLat; }
    public void setDeliveryLat(Double deliveryLat) { this.deliveryLat = deliveryLat; }
    public Double getDeliveryLng() { return deliveryLng; }
    public void setDeliveryLng(Double deliveryLng) { this.deliveryLng = deliveryLng; }
    public LocalTime getDepartureTime() { return departureTime; }
    public void setDepartureTime(LocalTime departureTime) { this.departureTime = departureTime; }
    public LocalTime getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(LocalTime arrivalTime) { this.arrivalTime = arrivalTime; }
    public boolean isCashAccepted() { return cashAccepted; }
    public void setCashAccepted(boolean cashAccepted) { this.cashAccepted = cashAccepted; }
    public String getWeekdays() { return weekdays; }
    public void setWeekdays(String weekdays) { this.weekdays = weekdays; }
    public Integer getHorizonDays() { return horizonDays; }
    public void setHorizonDays(Integer horizonDays) { this.horizonDays = horizonDays; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDate getLastGeneratedDate() { return lastGeneratedDate; }
    public void setLastGeneratedDate(LocalDate lastGeneratedDate) { this.lastGeneratedDate = lastGeneratedDate; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public Integer getWeekInterval() { return weekInterval; }
    public void setWeekInterval(Integer weekInterval) { this.weekInterval = weekInterval; }
    public Integer getPublicationLeadDays() { return publicationLeadDays; }
    public void setPublicationLeadDays(Integer publicationLeadDays) { this.publicationLeadDays = publicationLeadDays; }
    public Integer getHandoverLeadDays() { return handoverLeadDays; }
    public void setHandoverLeadDays(Integer handoverLeadDays) { this.handoverLeadDays = handoverLeadDays; }
    public PricingMode getPricingMode() { return pricingMode; }
    public void setPricingMode(PricingMode pricingMode) { this.pricingMode = pricingMode; }
    public boolean isNegotiable() { return negotiable; }
    public void setNegotiable(boolean negotiable) { this.negotiable = negotiable; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getRefusedCategories() { return refusedCategories; }
    public void setRefusedCategories(String refusedCategories) { this.refusedCategories = refusedCategories; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLastPublicationErrorCode() { return lastPublicationErrorCode; }
    public void setLastPublicationErrorCode(String value) { this.lastPublicationErrorCode = value; }
    public String getLastPublicationErrorMessage() { return lastPublicationErrorMessage; }
    public void setLastPublicationErrorMessage(String value) { this.lastPublicationErrorMessage = value; }
    public LocalDateTime getLastPublicationErrorAt() { return lastPublicationErrorAt; }
    public void setLastPublicationErrorAt(LocalDateTime value) { this.lastPublicationErrorAt = value; }
}
