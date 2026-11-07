package com.yadony.api.alerts;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Corridor cherché par un utilisateur connecté (V362) : une « alerte implicite ».
 *
 * <p>Une ligne par utilisateur et par corridor, mise à jour à chaque recherche. Si la
 * dernière recherche n'a rien trouvé ({@code resultCount == 0}) et qu'un trajet qui
 * correspond est publié, l'utilisateur est prévenu une fois ({@code notifiedAt}), puis la
 * ligne attend sa prochaine recherche. Aucune coordonnée : l'e-mail vient de Firebase.
 */
@Entity
@Table(name = "search_interests")
@Where(clause = "deleted_at IS NULL")
public class SearchInterestEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "departure_city", nullable = false, length = 100)
    private String departureCity;

    @Column(name = "arrival_city", nullable = false, length = 100)
    private String arrivalCity;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Column(name = "result_count", nullable = false)
    private int resultCount;

    @Column(name = "last_searched_at", nullable = false)
    private LocalDateTime lastSearchedAt;

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Column(name = "notified_announcement_id")
    private UUID notifiedAnnouncementId;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getDepartureCity() { return departureCity; }
    public void setDepartureCity(String departureCity) { this.departureCity = departureCity; }

    public String getArrivalCity() { return arrivalCity; }
    public void setArrivalCity(String arrivalCity) { this.arrivalCity = arrivalCity; }

    public LocalDate getDateFrom() { return dateFrom; }
    public void setDateFrom(LocalDate dateFrom) { this.dateFrom = dateFrom; }

    public LocalDate getDateTo() { return dateTo; }
    public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }

    public int getResultCount() { return resultCount; }
    public void setResultCount(int resultCount) { this.resultCount = resultCount; }

    public LocalDateTime getLastSearchedAt() { return lastSearchedAt; }
    public void setLastSearchedAt(LocalDateTime lastSearchedAt) { this.lastSearchedAt = lastSearchedAt; }

    public LocalDateTime getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(LocalDateTime notifiedAt) { this.notifiedAt = notifiedAt; }

    public UUID getNotifiedAnnouncementId() { return notifiedAnnouncementId; }
    public void setNotifiedAnnouncementId(UUID notifiedAnnouncementId) {
        this.notifiedAnnouncementId = notifiedAnnouncementId;
    }
}
