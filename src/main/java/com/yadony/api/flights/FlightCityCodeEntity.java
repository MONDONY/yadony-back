package com.yadony.api.flights;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

/**
 * Correspondance ville → code IATA déjà trouvée par l'autocomplétion Travelpayouts.
 *
 * <p>Les corridors Yadony sont saisis en noms de ville (« Abidjan ») ; l'API de prix
 * attend un code (ABJ). La première conversion d'une ville interroge Travelpayouts,
 * les suivantes lisent cette table. {@code iataCode} {@code null} = ville cherchée sans
 * résultat : on ne la redemande pas à chaque calcul de bons plans.
 */
@Entity
@Table(name = "flight_city_codes")
@Where(clause = "deleted_at IS NULL")
public class FlightCityCodeEntity extends BaseEntity {

    /** Nom normalisé (minuscules, sans accents ni espaces superflus), unique. */
    @Column(name = "city_key", nullable = false, length = 120, unique = true)
    private String cityKey;

    @Column(name = "city_name", nullable = false, length = 120)
    private String cityName;

    @Column(name = "iata_code", length = 3)
    private String iataCode;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    public String getCityKey() { return cityKey; }
    public void setCityKey(String cityKey) { this.cityKey = cityKey; }
    public String getCityName() { return cityName; }
    public void setCityName(String cityName) { this.cityName = cityName; }
    public String getIataCode() { return iataCode; }
    public void setIataCode(String iataCode) { this.iataCode = iataCode; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
}
