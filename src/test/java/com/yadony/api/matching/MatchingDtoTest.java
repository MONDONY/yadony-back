package com.yadony.api.matching;

import com.yadony.api.matching.dto.RefuseParcelRequest;
import com.yadony.api.matching.dto.TravelerStatsDto;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingDtoTest {

    @Test
    void travelerStatsDto_constructible() {
        TravelerStatsDto.DestinationStat dest = new TravelerStatsDto.DestinationStat("Paris", "Dakar", 5);
        TravelerStatsDto dto = new TravelerStatsDto(
                BigDecimal.valueOf(500), BigDecimal.valueOf(2000),
                3, 12, 0.95, BigDecimal.valueOf(4.8),
                List.of(dest),
                8, 2, 40, 3, 15,
                "EUR",
                List.of(new TravelerStatsDto.CurrencyRevenue("EUR", BigDecimal.valueOf(500))),
                List.of(new TravelerStatsDto.CurrencyRevenue("EUR", BigDecimal.valueOf(2000))));
        assertThat(dto.totalRevenue()).isEqualTo(BigDecimal.valueOf(2000));
        assertThat(dto.currency()).isEqualTo("EUR");
        assertThat(dto.totalRevenueByCurrency()).hasSize(1);
        assertThat(dto.topDestinations()).hasSize(1);
        assertThat(dto.totalTripsCompleted()).isEqualTo(8);
        assertThat(dto.activeTrips()).isEqualTo(2);
        assertThat(dto.ratingCount()).isEqualTo(15);
        assertThat(dest.from()).isEqualTo("Paris");
    }

    @Test
    void refuseParcelRequest_constructible() {
        RefuseParcelRequest req = new RefuseParcelRequest("Colis endommagé", null);
        assertThat(req.reason()).isEqualTo("Colis endommagé");
        assertThat(req.refusalPhotoUrl()).isNull();
    }

    @Test
    void announcementEntity_timezoneAndProGetters() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTimezone("Africa/Dakar");
        a.setTravelerIsPro(true);
        assertThat(a.getTimezone()).isEqualTo("Africa/Dakar");
        assertThat(a.isTravelerIsPro()).isTrue();
    }
}
