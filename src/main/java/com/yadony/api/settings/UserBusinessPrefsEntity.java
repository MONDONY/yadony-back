package com.yadony.api.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "user_business_preferences")
public class UserBusinessPrefsEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "weight_unit", nullable = false, length = 3)
    private String weightUnit = "kg";

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "EUR";

    // Devise d'affichage (presentment). NULL = automatique : suivre la devise
    // active. Jamais verrouillee par le solde, contrairement a currency_code.
    @Column(name = "display_currency_code", length = 3)
    private String displayCurrencyCode;

    @Column(name = "pickup_radius_km", nullable = false)
    private int pickupRadiusKm = 10;

    @Column(name = "default_package_weight_kg", nullable = false)
    private int defaultPackageWeightKg = 23;

    @Column(name = "min_bid_price_eur", nullable = false)
    private int minBidPriceEur = 0;

    @Column(name = "contact_mode", length = 10)
    private String contactMode;

    @Column(name = "response_delay_hours")
    private Integer responseDelayHours;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getWeightUnit() { return weightUnit; }
    public void setWeightUnit(String v) { this.weightUnit = v; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String v) { this.currencyCode = v; }
    public String getDisplayCurrencyCode() { return displayCurrencyCode; }
    public void setDisplayCurrencyCode(String v) { this.displayCurrencyCode = v; }
    public int getPickupRadiusKm() { return pickupRadiusKm; }
    public void setPickupRadiusKm(int v) { this.pickupRadiusKm = v; }
    public int getDefaultPackageWeightKg() { return defaultPackageWeightKg; }
    public void setDefaultPackageWeightKg(int v) { this.defaultPackageWeightKg = v; }
    public int getMinBidPriceEur() { return minBidPriceEur; }
    public void setMinBidPriceEur(int v) { this.minBidPriceEur = v; }
    public String getContactMode() { return contactMode; }
    public void setContactMode(String v) { this.contactMode = v; }
    public Integer getResponseDelayHours() { return responseDelayHours; }
    public void setResponseDelayHours(Integer v) { this.responseDelayHours = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
