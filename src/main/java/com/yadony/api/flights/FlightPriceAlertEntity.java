package com.yadony.api.flights;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Alerte prix d'un utilisateur sur un trajet aérien : prévenir quand le prix le plus bas
 * trouvé passe sous {@code maxPrice}.
 *
 * <p>{@code lastNotifiedPrice} porte l'anti-spam : une fois prévenu à un prix, on ne
 * renotifie que si le prix descend encore sous ce dernier prix annoncé. Il est remis à
 * {@code null} quand le prix repasse au-dessus du seuil, pour qu'une nouvelle baisse
 * sous le seuil soit annoncée.
 */
@Entity
@Table(name = "flight_price_alerts")
@Where(clause = "deleted_at IS NULL")
public class FlightPriceAlertEntity extends BaseEntity {

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "origin_code", nullable = false, length = 3)
    private String originCode;

    @Column(name = "destination_code", nullable = false, length = 3)
    private String destinationCode;

    @Column(name = "origin_label", length = 120)
    private String originLabel;

    @Column(name = "destination_label", length = 120)
    private String destinationLabel;

    /** Départ entre {@code departureFrom} et {@code departureTo} (inclus), {@code null} = toutes les dates à venir. */
    @Column(name = "departure_from")
    private LocalDate departureFrom;

    @Column(name = "departure_to")
    private LocalDate departureTo;

    /** Retour entre {@code returnFrom} et {@code returnTo} (inclus), {@code null} = aller simple. */
    @Column(name = "return_from")
    private LocalDate returnFrom;

    @Column(name = "return_to")
    private LocalDate returnTo;

    @Column(name = "direct_only", nullable = false)
    private boolean directOnly;

    @Column(name = "max_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal maxPrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_seen_price", precision = 12, scale = 2)
    private BigDecimal lastSeenPrice;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "last_notified_price", precision = 12, scale = 2)
    private BigDecimal lastNotifiedPrice;

    @Column(name = "last_notified_at")
    private LocalDateTime lastNotifiedAt;

    /**
     * Offres sous le seuil à la dernière vérification, {@code null} tant que l'alerte n'a jamais
     * été vérifiée, {@code 0} quand plus rien n'est sous le seuil.
     */
    @Column(name = "last_match_count")
    private Integer lastMatchCount;

    /** Jusqu'à trois codes IATA de ces offres, par prix croissant, séparés par des virgules. */
    @Column(name = "last_match_airlines", length = 32)
    private String lastMatchAirlines;

    /** Période surveillée (clé de regroupement et filtre du scheduler). */
    FlightAlertWindow window() {
        return new FlightAlertWindow(departureFrom, departureTo, returnFrom, returnTo);
    }

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public String getOriginCode() { return originCode; }
    public void setOriginCode(String originCode) { this.originCode = originCode; }
    public String getDestinationCode() { return destinationCode; }
    public void setDestinationCode(String destinationCode) { this.destinationCode = destinationCode; }
    public String getOriginLabel() { return originLabel; }
    public void setOriginLabel(String originLabel) { this.originLabel = originLabel; }
    public String getDestinationLabel() { return destinationLabel; }
    public void setDestinationLabel(String destinationLabel) { this.destinationLabel = destinationLabel; }
    public LocalDate getDepartureFrom() { return departureFrom; }
    public void setDepartureFrom(LocalDate departureFrom) { this.departureFrom = departureFrom; }
    public LocalDate getDepartureTo() { return departureTo; }
    public void setDepartureTo(LocalDate departureTo) { this.departureTo = departureTo; }
    public LocalDate getReturnFrom() { return returnFrom; }
    public void setReturnFrom(LocalDate returnFrom) { this.returnFrom = returnFrom; }
    public LocalDate getReturnTo() { return returnTo; }
    public void setReturnTo(LocalDate returnTo) { this.returnTo = returnTo; }
    public boolean isDirectOnly() { return directOnly; }
    public void setDirectOnly(boolean directOnly) { this.directOnly = directOnly; }
    public BigDecimal getMaxPrice() { return maxPrice; }
    public void setMaxPrice(BigDecimal maxPrice) { this.maxPrice = maxPrice; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public BigDecimal getLastSeenPrice() { return lastSeenPrice; }
    public void setLastSeenPrice(BigDecimal lastSeenPrice) { this.lastSeenPrice = lastSeenPrice; }
    public LocalDateTime getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(LocalDateTime lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public BigDecimal getLastNotifiedPrice() { return lastNotifiedPrice; }
    public void setLastNotifiedPrice(BigDecimal lastNotifiedPrice) { this.lastNotifiedPrice = lastNotifiedPrice; }
    public LocalDateTime getLastNotifiedAt() { return lastNotifiedAt; }
    public void setLastNotifiedAt(LocalDateTime lastNotifiedAt) { this.lastNotifiedAt = lastNotifiedAt; }
    public Integer getLastMatchCount() { return lastMatchCount; }
    public void setLastMatchCount(Integer lastMatchCount) { this.lastMatchCount = lastMatchCount; }
    public String getLastMatchAirlines() { return lastMatchAirlines; }
    public void setLastMatchAirlines(String lastMatchAirlines) { this.lastMatchAirlines = lastMatchAirlines; }
}
