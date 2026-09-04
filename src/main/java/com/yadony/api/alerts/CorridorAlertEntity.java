package com.yadony.api.alerts;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "corridor_alerts")
@Where(clause = "deleted_at IS NULL")
public class CorridorAlertEntity extends BaseEntity {

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 32)
    private AlertDirection direction = AlertDirection.TRAVELER_WANTS_PACKAGES;

    @Column(name = "departure_city", nullable = false, length = 100)
    private String departureCity;

    @Column(name = "departure_country_code", length = 2)
    private String departureCountryCode;

    @Column(name = "arrival_city", nullable = false, length = 100)
    private String arrivalCity;

    @Column(name = "arrival_country_code", length = 2)
    private String arrivalCountryCode;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Column(name = "min_weight_kg", precision = 5, scale = 2)
    private BigDecimal minWeightKg;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_notified_at")
    private LocalDateTime lastNotifiedAt;

    /**
     * Dernière ouverture des correspondances par le propriétaire. {@code null}
     * tant qu'il ne les a jamais consultées : tout ce qui matche est alors
     * « nouveau » pour lui. Indépendant de {@link #lastNotifiedAt}, qui ne
     * concerne que l'envoi des notifications.
     */
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "corridor_alert_content_categories",
            joinColumns = @JoinColumn(name = "alert_id")
    )
    @Column(name = "content_category", length = 100)
    private List<String> contentCategories = new ArrayList<>();

    // ── Zone de remise (option en plus du corridor, SENDER_WANTS_TRIPS) ──────
    // Quand center_lat/lng/radius_km sont présents, le matching ne garde que les
    // trajets dont le point de remise (pickup) est à ≤ radius_km du centre.
    @Column(name = "center_lat", precision = 9, scale = 6)
    private BigDecimal centerLat;

    @Column(name = "center_lng", precision = 9, scale = 6)
    private BigDecimal centerLng;

    @Column(name = "radius_km")
    private Integer radiusKm;

    @Column(name = "center_label", length = 160)
    private String centerLabel;

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    public AlertDirection getDirection() { return direction; }
    public void setDirection(AlertDirection direction) { this.direction = direction; }

    public String getDepartureCity() { return departureCity; }
    public void setDepartureCity(String departureCity) { this.departureCity = departureCity; }

    public String getDepartureCountryCode() { return departureCountryCode; }
    public void setDepartureCountryCode(String departureCountryCode) { this.departureCountryCode = departureCountryCode; }

    public String getArrivalCity() { return arrivalCity; }
    public void setArrivalCity(String arrivalCity) { this.arrivalCity = arrivalCity; }

    public String getArrivalCountryCode() { return arrivalCountryCode; }
    public void setArrivalCountryCode(String arrivalCountryCode) { this.arrivalCountryCode = arrivalCountryCode; }

    public LocalDate getDateFrom() { return dateFrom; }
    public void setDateFrom(LocalDate dateFrom) { this.dateFrom = dateFrom; }

    public LocalDate getDateTo() { return dateTo; }
    public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }

    public BigDecimal getMinWeightKg() { return minWeightKg; }
    public void setMinWeightKg(BigDecimal minWeightKg) { this.minWeightKg = minWeightKg; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getLastNotifiedAt() { return lastNotifiedAt; }
    public void setLastNotifiedAt(LocalDateTime lastNotifiedAt) { this.lastNotifiedAt = lastNotifiedAt; }

    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public List<String> getContentCategories() { return contentCategories; }
    public void setContentCategories(List<String> contentCategories) {
        this.contentCategories = contentCategories != null ? new ArrayList<>(contentCategories) : new ArrayList<>();
    }

    public BigDecimal getCenterLat() { return centerLat; }
    public void setCenterLat(BigDecimal centerLat) { this.centerLat = centerLat; }

    public BigDecimal getCenterLng() { return centerLng; }
    public void setCenterLng(BigDecimal centerLng) { this.centerLng = centerLng; }

    public Integer getRadiusKm() { return radiusKm; }
    public void setRadiusKm(Integer radiusKm) { this.radiusKm = radiusKm; }

    public String getCenterLabel() { return centerLabel; }
    public void setCenterLabel(String centerLabel) { this.centerLabel = centerLabel; }

    /** True si l'alerte porte une zone de remise complète (centre + rayon). */
    public boolean hasPickupZone() {
        return centerLat != null && centerLng != null && radiusKm != null;
    }
}
