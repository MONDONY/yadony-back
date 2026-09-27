package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminRatingResponse;
import com.yadony.api.admin.dto.ExcludeRatingRequest;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminRatingsControllerTest {

    @Mock RatingRepository ratingRepo;
    @Mock UserRepository userRepo;
    @Mock AuditService auditService;

    static final UUID ADMIN_ID = UUID.randomUUID();

    /** Admin authentifie : l'audit doit le designer comme acteur, jamais null. */
    static org.springframework.security.core.Authentication adminAuth() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new com.yadony.api.admin.account.AdminPrincipal(ADMIN_ID, "admin@yadony.test",
                        com.yadony.api.admin.account.AdminRole.ADMIN, false, "uid-admin"),
                null, List.of());
    }

    private AdminRatingsController controller() {
        return new AdminRatingsController(ratingRepo, userRepo, auditService);
    }

    // ---- listRatings ----

    @Test
    void listRatings_noFilter_returnsPage() {
        Page<RatingEntity> page = new PageImpl<>(List.of());
        when(ratingRepo.findAdminFiltered(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);
        when(userRepo.findAllById(any())).thenReturn(List.of());

        ResponseEntity<Page<AdminRatingResponse>> resp = controller().listRatings(null, null, null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTotalElements()).isEqualTo(0);
    }

    @Test
    void listRatings_withFlaggedFilter_passesFilter() {
        Page<RatingEntity> page = new PageImpl<>(List.of());
        when(ratingRepo.findAdminFiltered(eq(true), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);
        when(userRepo.findAllById(any())).thenReturn(List.of());

        ResponseEntity<Page<AdminRatingResponse>> resp = controller().listRatings(true, null, null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(ratingRepo).findAdminFiltered(eq(true), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void listRatings_withScoreFilter_passesMinMax() {
        Page<RatingEntity> page = new PageImpl<>(List.of());
        when(ratingRepo.findAdminFiltered(isNull(), eq(3), eq(5), any(Pageable.class)))
                .thenReturn(page);
        when(userRepo.findAllById(any())).thenReturn(List.of());

        ResponseEntity<Page<AdminRatingResponse>> resp = controller().listRatings(null, null, 3, 5, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(ratingRepo).findAdminFiltered(isNull(), eq(3), eq(5), any(Pageable.class));
    }

    @Test
    void listRatings_enrichesUserNames() {
        RatingEntity rating = new RatingEntity();
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        rating.setRaterId(fromId);
        rating.setRatedUserId(toId);
        rating.setStars(4);
        rating.setComment("Excellent");

        UserEntity fromUser = new UserEntity();
        fromUser.setFirstName("Alice");
        fromUser.setLastName("Martin");

        UserEntity toUser = new UserEntity();
        toUser.setFirstName("Bob");

        Page<RatingEntity> page = new PageImpl<>(List.of(rating));
        when(ratingRepo.findAdminFiltered(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);
        when(userRepo.findAllById(any())).thenReturn(List.of(fromUser, toUser));

        ResponseEntity<Page<AdminRatingResponse>> resp = controller().listRatings(null, null, null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getContent()).hasSize(1);
    }

    // ---- excludeRating ----

    @Test
    void excludeRating_setsFlaggedTrueAndAudits() {
        UUID id = UUID.randomUUID();
        RatingEntity rating = new RatingEntity();
        assertThat(rating.isFlagged()).isFalse();

        when(ratingRepo.findById(id)).thenReturn(Optional.of(rating));
        when(ratingRepo.save(rating)).thenReturn(rating);
        when(userRepo.findAllById(any())).thenReturn(List.of());

        ResponseEntity<AdminRatingResponse> resp = controller().excludeRating(id,
                new ExcludeRatingRequest(true, "farming détecté"), adminAuth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rating.isExcludedFromAverage()).isTrue();
        verify(ratingRepo).save(rating);
        verify(auditService).log(eq("RATING"), eq(id), eq("RATING_EXCLUDED"), eq(ADMIN_ID), anyMap());
    }

    @Test
    void exclude_setsExcludedAndAudits() {
        UUID id = UUID.randomUUID();
        RatingEntity rating = new RatingEntity();
        assertThat(rating.isExcludedFromAverage()).isFalse();

        when(ratingRepo.findById(id)).thenReturn(Optional.of(rating));
        when(ratingRepo.save(rating)).thenReturn(rating);
        when(userRepo.findAllById(any())).thenReturn(List.of());

        ResponseEntity<AdminRatingResponse> resp = controller().excludeRating(id,
                new ExcludeRatingRequest(true, "farming détecté"), adminAuth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rating.isExcludedFromAverage()).isTrue();
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().excluded()).isTrue();
        verify(ratingRepo).save(rating);
        verify(auditService).log(eq("RATING"), eq(id), eq("RATING_EXCLUDED"), eq(ADMIN_ID), anyMap());
    }

    @Test
    void excludeRating_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(ratingRepo.findById(id)).thenReturn(Optional.empty());

        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().excludeRating(id, new ExcludeRatingRequest(true, null), adminAuth()));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- deleteRating ----

    @Test
    void deleteRating_softDeletesAndAudits() {
        UUID id = UUID.randomUUID();
        RatingEntity rating = new RatingEntity();
        assertThat(rating.getDeletedAt()).isNull();

        when(ratingRepo.findById(id)).thenReturn(Optional.of(rating));

        ResponseEntity<Void> resp = controller().deleteRating(id, null, adminAuth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rating.getDeletedAt()).isNotNull();
        verify(ratingRepo).save(rating);
        verify(auditService).log(eq("RATING"), eq(id), eq("RATING_DELETED"), eq(ADMIN_ID), anyMap());
    }

    @Test
    void deleteRating_withReason_storesReasonInAudit() {
        UUID id = UUID.randomUUID();
        RatingEntity rating = new RatingEntity();
        when(ratingRepo.findById(id)).thenReturn(Optional.of(rating));

        controller().deleteRating(id, "  propos injurieux  ", adminAuth());

        verify(auditService).log(eq("RATING"), eq(id), eq("RATING_DELETED"), eq(ADMIN_ID),
                eq(java.util.Map.of("ratingId", id.toString(), "reason", "propos injurieux")));
    }

    @Test
    void deleteRating_withoutReason_storesEmptyReason() {
        UUID id = UUID.randomUUID();
        when(ratingRepo.findById(id)).thenReturn(Optional.of(new RatingEntity()));

        controller().deleteRating(id, null, adminAuth());

        verify(auditService).log(eq("RATING"), eq(id), eq("RATING_DELETED"), eq(ADMIN_ID),
                eq(java.util.Map.of("ratingId", id.toString(), "reason", "")));
    }

    @Test
    void deleteRating_reasonAt500Chars_isAccepted() {
        UUID id = UUID.randomUUID();
        when(ratingRepo.findById(id)).thenReturn(Optional.of(new RatingEntity()));
        String reason = "a".repeat(500);

        ResponseEntity<Void> resp = controller().deleteRating(id, reason, adminAuth());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(auditService).log(eq("RATING"), eq(id), eq("RATING_DELETED"), eq(ADMIN_ID),
                eq(java.util.Map.of("ratingId", id.toString(), "reason", reason)));
    }

    @Test
    void deleteRating_reasonTooLong_throws400_andDeletesNothing() {
        UUID id = UUID.randomUUID();

        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().deleteRating(id, "a".repeat(501), adminAuth()));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("rating-delete-reason-too-long");
        verify(ratingRepo, never()).findById(any());
        verify(ratingRepo, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void deleteRating_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(ratingRepo.findById(id)).thenReturn(Optional.empty());

        YadonyBusinessException ex = assertThrows(YadonyBusinessException.class,
                () -> controller().deleteRating(id, null, adminAuth()));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
