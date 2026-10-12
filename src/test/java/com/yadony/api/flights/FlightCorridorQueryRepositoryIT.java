package com.yadony.api.flights;

import com.yadony.api.alerts.CorridorAlertEntity;
import com.yadony.api.alerts.CorridorAlertRepository;
import com.yadony.api.flights.FlightCorridorQueryRepository.CityPair;
import com.yadony.api.flights.FlightCorridorQueryRepository.CodePair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FlightCorridorQueryRepositoryIT {

    @Autowired FlightCorridorQueryRepository repository;
    @Autowired CorridorAlertRepository corridorAlerts;
    @Autowired FlightSearchRepository searches;

    @Test
    void cityCorridors_distinct_ownerOnly_softDeletedIgnored() {
        UUID me = UUID.randomUUID();
        corridorAlerts.save(corridor(me, "Paris", "Abidjan"));
        corridorAlerts.save(corridor(me, "Paris", "Abidjan"));
        corridorAlerts.save(corridor(me, "Lyon", "Dakar"));
        CorridorAlertEntity deleted = corridor(me, "Paris", "Bamako");
        deleted.setDeletedAt(LocalDateTime.now());
        corridorAlerts.save(deleted);
        corridorAlerts.save(corridor(UUID.randomUUID(), "Paris", "Douala"));

        assertThat(repository.recentCityCorridors(me, 10))
                .containsExactlyInAnyOrder(new CityPair("Paris", "Abidjan"), new CityPair("Lyon", "Dakar"));
        assertThat(repository.recentCityCorridors(me, 1)).hasSize(1);
    }

    @Test
    void searchedRoutes_distinct_ownerOnly() {
        UUID me = UUID.randomUUID();
        searches.save(search(me, "PAR", "ABJ"));
        searches.save(search(me, "PAR", "ABJ"));
        searches.save(search(me, "PAR", "DKR"));
        searches.save(search(UUID.randomUUID(), "LYS", "CMN"));

        assertThat(repository.recentSearchedRoutes(me, 10))
                .containsExactlyInAnyOrder(new CodePair("PAR", "ABJ"), new CodePair("PAR", "DKR"));
    }

    @Test
    void userWithoutAnything_hasNoCorridors() {
        UUID nobody = UUID.randomUUID();
        assertThat(repository.recentCityCorridors(nobody, 10)).isEmpty();
        assertThat(repository.recentSearchedRoutes(nobody, 10)).isEmpty();
    }

    private static CorridorAlertEntity corridor(UUID owner, String from, String to) {
        CorridorAlertEntity a = new CorridorAlertEntity();
        a.setOwnerId(owner);
        a.setDepartureCity(from);
        a.setArrivalCity(to);
        a.setActive(true);
        return a;
    }

    private static FlightSearchEntity search(UUID owner, String from, String to) {
        FlightSearchEntity s = new FlightSearchEntity();
        s.setOwnerId(owner);
        s.setOriginCode(from);
        s.setDestinationCode(to);
        return s;
    }
}
