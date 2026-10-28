package com.yadony.api.tracking;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Position floue d'un voyageur : ville proche et pourcentage de trajet. Les coordonnées brutes ne
 * sont jamais stockées.
 */
@Entity
@Table(name = "trip_position_samples")
@Where(clause = "deleted_at IS NULL")
public class TripPositionSampleEntity extends BaseEntity {

    @Column(name = "announcement_id", nullable = false)
    private UUID announcementId;

    @Column(name = "captured_at", nullable = false)
    private OffsetDateTime capturedAt;

    @Column(name = "city_name", nullable = false, length = 120)
    private String cityName;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    /** 0 à 100 ; null quand la position est trop éloignée de la ligne départ → arrivée. */
    @Column(name = "progress_pct")
    private Integer progressPct;

    @Column(name = "source", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SampleSource source;

    protected TripPositionSampleEntity() {
    }

    public TripPositionSampleEntity(UUID announcementId, OffsetDateTime capturedAt, String cityName,
                                    String countryCode, Integer progressPct, SampleSource source) {
        this.announcementId = announcementId;
        this.capturedAt = capturedAt;
        this.cityName = cityName;
        this.countryCode = countryCode;
        this.progressPct = progressPct;
        this.source = source;
    }

    public UUID getAnnouncementId() { return announcementId; }
    public OffsetDateTime getCapturedAt() { return capturedAt; }
    public String getCityName() { return cityName; }
    public String getCountryCode() { return countryCode; }
    public Integer getProgressPct() { return progressPct; }
    public SampleSource getSource() { return source; }
}
