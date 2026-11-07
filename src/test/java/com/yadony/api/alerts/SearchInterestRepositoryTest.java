package com.yadony.api.alerts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SearchInterestRepositoryTest {

    @Autowired SearchInterestRepository repository;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 10, 0);

    private SearchInterestEntity interest(UUID userId, String dep, String arr, int results,
                                          LocalDateTime searchedAt, LocalDateTime notifiedAt) {
        SearchInterestEntity s = new SearchInterestEntity();
        s.setUserId(userId);
        s.setDepartureCity(dep);
        s.setArrivalCity(arr);
        s.setResultCount(results);
        s.setLastSearchedAt(searchedAt);
        s.setNotifiedAt(notifiedAt);
        return repository.saveAndFlush(s);
    }

    @Test
    void findForCorridor_ignoresCase_andScopesToTheUser() {
        UUID me = UUID.randomUUID();
        SearchInterestEntity mine = interest(me, "Paris", "Abidjan", 0, NOW, null);
        interest(UUID.randomUUID(), "Paris", "Abidjan", 0, NOW, null);

        assertThat(repository.findForCorridor(me, "PARIS", "abidjan")).contains(mine);
        assertThat(repository.findForCorridor(me, "Paris", "Dakar")).isEmpty();
    }

    @Test
    void findPendingForCorridor_keepsRecentEmptySearchesNotYetSuggested() {
        UUID a = UUID.randomUUID();
        SearchInterestEntity pending = interest(a, "paris", "ABIDJAN", 0, NOW.minusDays(3), null);
        interest(UUID.randomUUID(), "Paris", "Abidjan", 2, NOW.minusDays(1), null);           // avait trouvé
        interest(UUID.randomUUID(), "Paris", "Abidjan", 0, NOW.minusDays(1), NOW.minusHours(1)); // déjà prévenu
        interest(UUID.randomUUID(), "Paris", "Abidjan", 0, NOW.minusDays(45), null);          // trop ancien
        interest(UUID.randomUUID(), "Lyon", "Abidjan", 0, NOW, null);                         // autre corridor

        List<SearchInterestEntity> found = repository.findPendingForCorridor("Paris", "Abidjan", NOW.minusDays(30));

        assertThat(found).containsExactly(pending);
    }

    @Test
    void weeklyCount_onlyCountsRecentSuggestionsOfThatUser() {
        UUID me = UUID.randomUUID();
        interest(me, "Paris", "Abidjan", 0, NOW, NOW.minusDays(2));
        interest(me, "Paris", "Dakar", 0, NOW, NOW.minusDays(10));
        interest(me, "Lyon", "Dakar", 0, NOW, null);
        interest(UUID.randomUUID(), "Paris", "Abidjan", 0, NOW, NOW.minusDays(1));

        assertThat(repository.countByUserIdAndNotifiedAtAfter(me, NOW.minusDays(7))).isEqualTo(1);
    }

    @Test
    void purges_eraseOldSearches_andAllSearchesOfAUser() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        interest(me, "Paris", "Abidjan", 0, NOW.minusDays(40), null);
        interest(me, "Paris", "Dakar", 0, NOW, null);
        SearchInterestEntity kept = interest(other, "Lyon", "Bamako", 0, NOW, null);

        assertThat(repository.purgeSearchedBefore(NOW.minusDays(30))).isEqualTo(1);
        assertThat(repository.findAllByUserIdOrderByLastSearchedAtDesc(me)).hasSize(1);

        assertThat(repository.purgeByUserId(me)).isEqualTo(1);
        assertThat(repository.findAllByUserIdOrderByLastSearchedAtDesc(me)).isEmpty();
        assertThat(repository.findAll()).containsExactly(kept);
    }
}
