package com.yadony.api.ratings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Requêtes qui alimentent le contexte d'un avis : habitudes du notant, avis exclus du noté. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RatingRepositoryContextTest {

    @Autowired RatingRepository ratingRepository;
    @Autowired TestEntityManager em;

    private RatingEntity rating(UUID rater, UUID rated, int stars, boolean excluded) {
        RatingEntity r = new RatingEntity();
        r.setRaterId(rater);
        r.setRatedUserId(rated);
        r.setBidId(UUID.randomUUID());
        r.setStars(stars);
        r.setExcludedFromAverage(excluded);
        return em.persistAndFlush(r);
    }

    @Test
    @DisplayName("notes émises : comptées, moyennées, et dont les basses, sans mélanger les notants")
    void raterHabits() {
        UUID rater = UUID.randomUUID();
        UUID rated = UUID.randomUUID();
        rating(rater, rated, 1, false);
        rating(rater, rated, 2, false);
        rating(rater, UUID.randomUUID(), 5, false);
        rating(UUID.randomUUID(), rated, 1, false);

        assertThat(ratingRepository.countByRaterId(rater)).isEqualTo(3);
        assertThat(ratingRepository.averageStarsByRaterId(rater)).isCloseTo(8.0 / 3, within(0.001));
        assertThat(ratingRepository.countByRaterIdAndStarsLessThanEqual(rater, 2)).isEqualTo(2);
    }

    @Test
    @DisplayName("un notant sans note émise n'a pas de moyenne")
    void noAverageWithoutRatings() {
        assertThat(ratingRepository.averageStarsByRaterId(UUID.randomUUID())).isNull();
    }

    @Test
    @DisplayName("notes d'un même notant à un même noté")
    void sameRelation() {
        UUID rater = UUID.randomUUID();
        UUID rated = UUID.randomUUID();
        rating(rater, rated, 1, false);
        rating(rater, rated, 1, false);
        rating(rater, UUID.randomUUID(), 1, false);
        rating(UUID.randomUUID(), rated, 1, false);

        assertThat(ratingRepository.countByRaterIdAndRatedUserId(rater, rated)).isEqualTo(2);
    }

    @Test
    @DisplayName("avis du noté sortis de sa moyenne, un avis supprimé n'étant pas compté")
    void excludedCount() {
        UUID rated = UUID.randomUUID();
        rating(UUID.randomUUID(), rated, 1, true);
        rating(UUID.randomUUID(), rated, 1, true);
        rating(UUID.randomUUID(), rated, 5, false);
        RatingEntity gone = rating(UUID.randomUUID(), rated, 1, true);
        gone.setDeletedAt(java.time.LocalDateTime.now());
        em.persistAndFlush(gone);
        em.clear();

        assertThat(ratingRepository.countByRatedUserIdAndExcludedFromAverageTrue(rated)).isEqualTo(2);
    }
}
