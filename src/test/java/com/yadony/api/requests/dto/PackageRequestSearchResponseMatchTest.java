package com.yadony.api.requests.dto;

import com.yadony.api.matching.MatchingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PackageRequestSearchResponseMatchTest {

    private PackageRequestSearchResponse base(UUID id) {
        return new PackageRequestSearchResponse(
                id, "Paris", "Dakar",
                null, null, null, null,
                LocalDate.of(2026, 8, 10), 5,
                new BigDecimal("2"), null, null, null,
                new BigDecimal("40"), true, null,
                null, null,
                new PackageRequestSearchResponse.SenderPublicProfile(
                        UUID.randomUUID(), "Fatou S.", 4.9, 12, true, null),
                Set.of(), List.of(), false, false,
                null, null, null, "EUR");
    }

    @Test
    void withMatch_renseigneLesTroisChampsSansToucherAuReste() {
        UUID id = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        PackageRequestSearchResponse sans = base(id);

        PackageRequestSearchResponse avec = sans.withMatch(
                new MatchingService.MatchInfo(id, tripId, LocalDate.of(2026, 8, 12), 94));

        assertThat(avec.matchScore()).isEqualTo(94);
        assertThat(avec.matchedTripId()).isEqualTo(tripId);
        assertThat(avec.matchedTripDepartureDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(avec.id()).isEqualTo(id);
        assertThat(avec.departureCity()).isEqualTo("Paris");
        assertThat(avec.sender().displayName()).isEqualTo("Fatou S.");
    }

    @Test
    void sansMatch_lesTroisChampsSontNuls() {
        PackageRequestSearchResponse sans = base(UUID.randomUUID());

        assertThat(sans.matchScore()).isNull();
        assertThat(sans.matchedTripId()).isNull();
        assertThat(sans.matchedTripDepartureDate()).isNull();
    }
}
