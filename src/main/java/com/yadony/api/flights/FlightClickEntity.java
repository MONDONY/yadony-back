package com.yadony.api.flights;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Clic vers une offre du partenaire. Mesure l'usage de la fonctionnalité et, quand le
 * sous-identifiant est activé, permet de rapprocher une commission Travelpayouts d'un clic.
 */
@Entity
@Table(name = "flight_clicks")
@Where(clause = "deleted_at IS NULL")
public class FlightClickEntity extends BaseEntity {

    /** Nul pour un visiteur sans compte. */
    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "origin_code", nullable = false, length = 3)
    private String originCode;

    @Column(name = "destination_code", nullable = false, length = 3)
    private String destinationCode;

    @Column(name = "price", precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "airline_code", length = 3)
    private String airlineCode;

    @Column(name = "gate", length = 100)
    private String gate;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "sub_id", nullable = false, length = 32, unique = true)
    private String subId;

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public String getOriginCode() { return originCode; }
    public void setOriginCode(String originCode) { this.originCode = originCode; }
    public String getDestinationCode() { return destinationCode; }
    public void setDestinationCode(String destinationCode) { this.destinationCode = destinationCode; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getAirlineCode() { return airlineCode; }
    public void setAirlineCode(String airlineCode) { this.airlineCode = airlineCode; }
    public String getGate() { return gate; }
    public void setGate(String gate) { this.gate = gate; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSubId() { return subId; }
    public void setSubId(String subId) { this.subId = subId; }
}
