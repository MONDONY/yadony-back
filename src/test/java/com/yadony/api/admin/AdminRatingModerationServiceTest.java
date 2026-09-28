package com.yadony.api.admin;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.ratings.RatingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminRatingModerationServiceTest {

    @Mock RatingRepository ratingRepo;
    @Mock AuditService auditService;
    @Mock RatingService ratingService;

    private final UUID adminId = UUID.randomUUID();
    private final UUID ratingId = UUID.randomUUID();
    private final UUID ratedUserId = UUID.randomUUID();

    private AdminRatingModerationService service() {
        return new AdminRatingModerationService(ratingRepo, auditService, ratingService);
    }

    private RatingEntity rating() {
        RatingEntity r = new RatingEntity();
        ReflectionTestUtils.setField(r, "id", ratingId);
        r.setRatedUserId(ratedUserId);
        return r;
    }

    @Test
    void exclude_sortDeLaMoyenneRecalculeEtAudite() {
        RatingEntity r = rating();
        when(ratingRepo.findById(ratingId)).thenReturn(Optional.of(r));

        RatingEntity result = service().exclude(ratingId, true, "insultes", adminId);

        assertThat(result.isExcludedFromAverage()).isTrue();
        assertThat(result.getExcludedReason()).isEqualTo("insultes");
        InOrder order = inOrder(ratingRepo, ratingService, auditService);
        order.verify(ratingRepo).save(r);
        order.verify(ratingService).recalculateAverageRating(ratedUserId);
        order.verify(auditService).log("RATING", ratingId, "RATING_EXCLUDED", adminId,
                Map.of("ratingId", ratingId.toString(), "reason", "insultes"));
    }

    @Test
    void exclude_retourDansLaMoyenne_effaceLeMotif() {
        RatingEntity r = rating();
        r.setExcludedFromAverage(true);
        r.setExcludedReason("ancien");
        when(ratingRepo.findById(ratingId)).thenReturn(Optional.of(r));

        service().exclude(ratingId, false, null, adminId);

        assertThat(r.isExcludedFromAverage()).isFalse();
        assertThat(r.getExcludedReason()).isNull();
        verify(auditService).log("RATING", ratingId, "RATING_EXCLUDED", adminId,
                Map.of("ratingId", ratingId.toString(), "reason", ""));
    }

    @Test
    void exclude_avisIntrouvable_404() {
        when(ratingRepo.findById(ratingId)).thenReturn(Optional.empty());
        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> service().exclude(ratingId, true, "x", adminId));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("rating-not-found");
    }

    @Test
    void delete_suppressionDouceFlushRecalculPuisAudit() {
        RatingEntity r = rating();
        when(ratingRepo.findById(ratingId)).thenReturn(Optional.of(r));

        service().delete(ratingId, "  spam  ", adminId);

        assertThat(r.getDeletedAt()).isNotNull();
        InOrder order = inOrder(ratingRepo, ratingService, auditService);
        order.verify(ratingRepo).saveAndFlush(r);
        order.verify(ratingService).recalculateAverageRating(ratedUserId);
        order.verify(auditService).log("RATING", ratingId, "RATING_DELETED", adminId,
                Map.of("ratingId", ratingId.toString(), "reason", "spam"));
    }

    @Test
    void delete_motifTropLong_400SansToucherLAvis() {
        String tropLong = "x".repeat(AdminRatingModerationService.MAX_DELETE_REASON_LENGTH + 1);
        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> service().delete(ratingId, tropLong, adminId));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("rating-delete-reason-too-long");
        verifyNoInteractions(ratingRepo, auditService, ratingService);
    }

    @Test
    void delete_sansMotif_auditeUnMotifVide() {
        RatingEntity r = rating();
        when(ratingRepo.findById(ratingId)).thenReturn(Optional.of(r));

        service().delete(ratingId, null, adminId);

        verify(auditService).log(eq("RATING"), eq(ratingId), eq("RATING_DELETED"), eq(adminId),
                eq(Map.of("ratingId", ratingId.toString(), "reason", "")));
    }

    @Test
    void recalcul_ignoreUnAvisSansCompteNote() {
        RatingEntity r = rating();
        r.setRatedUserId(null);
        when(ratingRepo.findById(ratingId)).thenReturn(Optional.of(r));

        service().delete(ratingId, "", adminId);

        verify(ratingService, never()).recalculateAverageRating(any());
    }
}
