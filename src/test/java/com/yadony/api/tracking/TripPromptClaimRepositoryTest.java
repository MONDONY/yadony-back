package com.yadony.api.tracking;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Les UPDATE de réservation et les requêtes candidates, contre le vrai dépôt. */
@DataJpaTest
@ActiveProfiles("test")
class TripPromptClaimRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);

    @Autowired AnnouncementRepository repository;
    @Autowired EntityManager em;

    private AnnouncementEntity save(AnnouncementStatus status, LocalTime arrivalTime) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(DAY);
        a.setArrivalDate(DAY);
        a.setArrivalTime(arrivalTime);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG");
        a.setPickupLat(new BigDecimal("49.009000"));
        a.setPickupLng(new BigDecimal("2.547000"));
        a.setDeliveryAddressLabel("LSS");
        a.setDeliveryLat(new BigDecimal("14.739000"));
        a.setDeliveryLng(new BigDecimal("-17.490000"));
        a.setAvailableKg(new BigDecimal("20"));
        a.setTotalKg(new BigDecimal("20"));
        a.setReservedKg(BigDecimal.ZERO);
        a.setPricePerKg(new BigDecimal("5"));
        a.setTimezone("Europe/Paris");
        a.setStatus(status);
        return repository.saveAndFlush(a);
    }

    @Test
    void claimDeparturePromptTakesTheRowOnlyOnce() {
        UUID id = save(AnnouncementStatus.ACTIVE, null).getId();
        OffsetDateTime now = OffsetDateTime.parse("2026-11-01T10:00:00Z");

        assertThat(repository.claimDeparturePrompt(id, now)).isEqualTo(1);
        assertThat(repository.claimDeparturePrompt(id, now.plusMinutes(5))).isZero();

        em.clear();
        AnnouncementEntity reread = repository.findById(id).orElseThrow();
        // Non nul seulement : la colonne H2 est un TIMESTAMP sans fuseau, l'instant relu dépend du fuseau de la JVM.
        assertThat(reread.getDeparturePromptSentAt()).isNotNull();
        assertThat(reread.getArrivalPromptSentAt()).isNull();
    }

    @Test
    void claimArrivalPromptTakesTheRowOnlyOnce() {
        UUID id = save(AnnouncementStatus.ACTIVE, LocalTime.of(6, 30)).getId();
        OffsetDateTime now = OffsetDateTime.parse("2026-11-01T10:00:00Z");

        assertThat(repository.claimArrivalPrompt(id, now)).isEqualTo(1);
        assertThat(repository.claimArrivalPrompt(id, now.plusMinutes(5))).isZero();

        em.clear();
        AnnouncementEntity reread = repository.findById(id).orElseThrow();
        assertThat(reread.getArrivalPromptSentAt()).isNotNull();
        assertThat(reread.getDeparturePromptSentAt()).isNull();
    }

    @Test
    void departureCandidatesAreFilteredByStatusDateAndSentColumn() {
        AnnouncementEntity ok = save(AnnouncementStatus.ACTIVE, null);
        AnnouncementEntity inProgress = save(AnnouncementStatus.IN_PROGRESS, null);
        save(AnnouncementStatus.CANCELLED, null);
        save(AnnouncementStatus.DRAFT, null);
        AnnouncementEntity alreadySent = save(AnnouncementStatus.ACTIVE, null);
        repository.claimDeparturePrompt(alreadySent.getId(), OffsetDateTime.now());
        em.clear();

        List<UUID> ids = repository.findDeparturePromptCandidates(DAY.minusDays(1), DAY.plusDays(1))
                .stream().map(AnnouncementEntity::getId).toList();
        assertThat(ids).containsExactlyInAnyOrder(ok.getId(), inProgress.getId());
        assertThat(repository.findDeparturePromptCandidates(DAY.plusDays(2), DAY.plusDays(3))).isEmpty();
    }

    @Test
    void arrivalCandidatesNeedAnArrivalTimeAndAnOpenStatus() {
        AnnouncementEntity ok = save(AnnouncementStatus.IN_PROGRESS, LocalTime.of(6, 30));
        save(AnnouncementStatus.ACTIVE, null);
        save(AnnouncementStatus.CANCELLED, LocalTime.of(6, 30));
        save(AnnouncementStatus.DRAFT, LocalTime.of(6, 30));
        AnnouncementEntity alreadySent = save(AnnouncementStatus.ACTIVE, LocalTime.of(6, 30));
        repository.claimArrivalPrompt(alreadySent.getId(), OffsetDateTime.now());
        em.clear();

        List<UUID> ids = repository.findArrivalPromptCandidates(DAY.minusDays(3), DAY.plusDays(1))
                .stream().map(AnnouncementEntity::getId).toList();
        assertThat(ids).containsExactly(ok.getId());
        assertThat(repository.findArrivalPromptCandidates(DAY.plusDays(2), DAY.plusDays(3))).isEmpty();
    }
}
