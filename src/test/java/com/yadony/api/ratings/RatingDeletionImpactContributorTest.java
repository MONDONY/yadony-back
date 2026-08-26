package com.yadony.api.ratings;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RatingDeletionImpactContributorTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock RatingRepository ratingRepository;

    @Test
    @DisplayName("les notes données sont un constat informatif, jamais bloquant")
    void ratingsGiven_areInformational() {
        when(ratingRepository.countByRaterId(USER_ID)).thenReturn(7L);

        List<ImpactFinding> findings =
                new RatingDeletionImpactContributor(ratingRepository).contribute(USER_ID);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().severity()).isEqualTo(ImpactSeverity.INFO);
        assertThat(findings.getFirst().code()).isEqualTo("RATINGS_GIVEN");
        assertThat(findings.getFirst().count()).isEqualTo(7);
    }

    @Test
    @DisplayName("aucune note donnée, rien à rapporter")
    void noRating_reportsNothing() {
        when(ratingRepository.countByRaterId(USER_ID)).thenReturn(0L);

        assertThat(new RatingDeletionImpactContributor(ratingRepository).contribute(USER_ID))
                .isEmpty();
    }
}
