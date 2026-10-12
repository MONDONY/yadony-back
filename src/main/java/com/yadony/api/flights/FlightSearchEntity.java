package com.yadony.api.flights;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * Recherche de vols faite par un utilisateur. Sert une seule chose : proposer des bons
 * plans sur les trajets qu'il a cherchés récemment. Ni prix, ni résultat n'y sont gardés.
 */
@Entity
@Table(name = "flight_searches")
@Where(clause = "deleted_at IS NULL")
public class FlightSearchEntity extends BaseEntity {

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "origin_code", nullable = false, length = 3)
    private String originCode;

    @Column(name = "destination_code", nullable = false, length = 3)
    private String destinationCode;

    @Column(name = "round_trip", nullable = false)
    private boolean roundTrip;

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public String getOriginCode() { return originCode; }
    public void setOriginCode(String originCode) { this.originCode = originCode; }
    public String getDestinationCode() { return destinationCode; }
    public void setDestinationCode(String destinationCode) { this.destinationCode = destinationCode; }
    public boolean isRoundTrip() { return roundTrip; }
    public void setRoundTrip(boolean roundTrip) { this.roundTrip = roundTrip; }
}
