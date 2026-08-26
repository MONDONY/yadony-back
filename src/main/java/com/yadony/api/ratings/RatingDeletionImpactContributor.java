package com.yadony.api.ratings;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import com.yadony.api.common.deletion.UserDeletionImpactContributor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Les notes données par le compte survivent à son anonymisation et continuent de peser sur la
 * moyenne de ceux qu'il a notés, désormais au nom d'un compte fantôme.
 *
 * <p>Purement informatif : aucune contrepartie n'est nommée, un tel décompte peut atteindre des
 * centaines d'entrées et noierait les avertissements qui appellent, eux, une action.
 */
@Component
public class RatingDeletionImpactContributor implements UserDeletionImpactContributor {

    private final RatingRepository ratingRepository;

    public RatingDeletionImpactContributor(RatingRepository ratingRepository) {
        this.ratingRepository = ratingRepository;
    }

    @Override
    public List<ImpactFinding> contribute(UUID userId) {
        long given = ratingRepository.countByRaterId(userId);
        if (given == 0) {
            return List.of();
        }
        return List.of(ImpactFinding.plain(ImpactSeverity.INFO, "RATINGS_GIVEN", Math.toIntExact(given)));
    }
}
