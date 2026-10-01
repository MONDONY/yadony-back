package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementInsightsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnnouncementViewServiceTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AnnouncementViewRepository viewRepository;

    private AnnouncementViewService service;

    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AnnouncementViewService(announcementRepository, viewRepository);
    }

    private AnnouncementEntity trip(boolean listed) {
        AnnouncementEntity a = mock(AnnouncementEntity.class, withSettings().strictness(Strictness.LENIENT));
        when(a.getId()).thenReturn(announcementId);
        when(a.getTravelerId()).thenReturn(travelerId);
        when(a.isPubliclyListable()).thenReturn(listed);
        when(a.getShareViewCount()).thenReturn(7L);
        return a;
    }

    /** Mock construit AVANT le stub du repository : Mockito refuse un stub imbriqué. */
    private void givenTrip(boolean listed) {
        AnnouncementEntity a = trip(listed);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(a));
    }

    // ─── recordView ──────────────────────────────────────────────────────────

    @Test
    void recordView_firstViewOfAListedTrip_isRecordedForThatViewer() {
        givenTrip(true);

        service.recordView(senderId, announcementId);

        ArgumentCaptor<AnnouncementViewEntity> saved = ArgumentCaptor.forClass(AnnouncementViewEntity.class);
        verify(viewRepository).save(saved.capture());
        assertThat(saved.getValue().getAnnouncementId()).isEqualTo(announcementId);
        assertThat(saved.getValue().getViewerId()).isEqualTo(senderId);
    }

    @Test
    void recordView_sameViewerAgain_isNotCountedTwice() {
        givenTrip(true);
        when(viewRepository.existsByAnnouncementIdAndViewerId(announcementId, senderId)).thenReturn(true);

        service.recordView(senderId, announcementId);

        verify(viewRepository, never()).save(any());
    }

    @Test
    void recordView_ownerOrUnlistedTrip_isNotCounted() {
        givenTrip(true);
        service.recordView(travelerId, announcementId);

        givenTrip(false);
        service.recordView(senderId, announcementId);

        verify(viewRepository, never()).save(any());
    }

    @Test
    void recordView_withoutViewer_touchesNothing() {
        service.recordView(null, announcementId);

        verifyNoInteractions(announcementRepository, viewRepository);
    }

    @Test
    void recordView_unknownTrip_isNotFound() {
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordView(senderId, announcementId))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void recordView_concurrentDuplicate_isSwallowed() {
        givenTrip(true);
        when(viewRepository.save(any())).thenThrow(new DataIntegrityViolationException("uq_announcement_views"));

        service.recordView(senderId, announcementId);

        verify(viewRepository).save(any());
    }

    // ─── getInsights ─────────────────────────────────────────────────────────

    @Test
    void getInsights_owner_returnsUniqueViewersAndPosterViews() {
        givenTrip(true);
        when(viewRepository.countByAnnouncementId(announcementId)).thenReturn(12L);

        AnnouncementInsightsResponse insights = service.getInsights(travelerId, announcementId);

        assertThat(insights.uniqueViewerCount()).isEqualTo(12L);
        assertThat(insights.shareViewCount()).isEqualTo(7L);
    }

    @Test
    void getInsights_someoneElse_isNotFound() {
        givenTrip(true);

        assertThatThrownBy(() -> service.getInsights(senderId, announcementId))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(viewRepository, never()).countByAnnouncementId(any());
    }
}
