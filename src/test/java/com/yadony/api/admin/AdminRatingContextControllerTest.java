package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminRatingContextResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminRatingContextControllerTest {

    @Mock RatingRepository ratingRepo;
    @Mock RatingContextService contextService;

    @Test
    void context_rendLeContexteDeLAvis_memeSupprime() {
        UUID id = UUID.randomUUID();
        RatingEntity rating = new RatingEntity();
        var body = new AdminRatingContextResponse(null, null, 0, List.of(), 0, List.of());
        // findByIdIncludingDeleted : la corbeille sert à décider d'une restauration.
        when(ratingRepo.findByIdIncludingDeleted(id)).thenReturn(Optional.of(rating));
        when(contextService.build(rating)).thenReturn(body);

        var resp = new AdminRatingContextController(ratingRepo, contextService).context(id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isSameAs(body);
    }

    @Test
    void context_avisInconnu_404() {
        UUID id = UUID.randomUUID();
        when(ratingRepo.findByIdIncludingDeleted(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new AdminRatingContextController(ratingRepo, contextService).context(id))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(contextService);
    }
}
