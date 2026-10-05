package com.yadony.api.ratings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.reception.BidRecipientLinkRepository;
import com.yadony.api.matching.reception.ReceptionLinkStatus;
import com.yadony.api.ratings.dto.RatingResponse;
import com.yadony.api.ratings.dto.ReceptionRatingRequest;
import com.yadony.api.ratings.events.RatingCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceptionRatingServiceTest {

    private static final String UID = "uid-recipient";
    private static final String TOKEN = "tok-reception";

    @Mock RatingRepository ratingRepository;
    @Mock RatingService ratingService;
    @Mock BidRepository bidRepository;
    @Mock BidRecipientLinkRepository linkRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks ReceptionRatingService service;

    private final UUID recipientId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID annId = UUID.randomUUID();
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        UserEntity recipient = new UserEntity();
        ReflectionTestUtils.setField(recipient, "id", recipientId);
        lenient().when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(recipient));

        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setAnnouncementId(annId);
        bid.setStatus(BidStatus.COMPLETED);
        bid.setTrackingToken(TOKEN);
        lenient().when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));

        AnnouncementEntity ann = new AnnouncementEntity();
        ann.setTravelerId(travelerId);
        lenient().when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
    }

    private void confirmedRecipient(boolean confirmed) {
        when(linkRepository.existsByBidIdAndRecipientUserIdAndStatus(
                bid.getId(), recipientId, ReceptionLinkStatus.CONFIRMED)).thenReturn(confirmed);
    }

    @Test
    void rate_confirmedRecipientDelivered_savesWithTokenRecalculatesAuditsAndPublishes() {
        confirmedRecipient(true);
        when(ratingRepository.recipientHasRated(bid.getId(), recipientId, TOKEN)).thenReturn(false);
        when(ratingRepository.saveAndFlush(any(RatingEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        RatingResponse response = service.rate(bid.getId(), UID, new ReceptionRatingRequest(5, "Parfait"));

        ArgumentCaptor<RatingEntity> saved = ArgumentCaptor.forClass(RatingEntity.class);
        verify(ratingRepository).saveAndFlush(saved.capture());
        RatingEntity r = saved.getValue();
        assertThat(r.getRaterId()).isEqualTo(recipientId);
        assertThat(r.getRatedUserId()).isEqualTo(travelerId);
        assertThat(r.getBidId()).isEqualTo(bid.getId());
        assertThat(r.getTrackingToken()).isEqualTo(TOKEN);
        assertThat(r.getStars()).isEqualTo(5);
        assertThat(r.getComment()).isEqualTo("Parfait");
        assertThat(response.ratedUserId()).isEqualTo(travelerId);
        assertThat(response.stars()).isEqualTo(5);

        verify(ratingService).recalculateAverageRating(travelerId);
        verify(auditService).log(eq("RATING"), any(), eq("RECEPTION_RATING_CREATED"), eq(recipientId),
                eq(Map.of("bidId", bid.getId().toString(), "stars", 5)));
        ArgumentCaptor<RatingCreatedEvent> event = ArgumentCaptor.forClass(RatingCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getRatedUserId()).isEqualTo(travelerId);
        assertThat(event.getValue().getRaterId()).isEqualTo(recipientId);
        assertThat(event.getValue().getStars()).isEqualTo(5);
    }

    @Test
    void rate_notConfirmedRecipient_is403() {
        confirmedRecipient(false);

        YadonyBusinessException e = catchThrowableOfType(
                () -> service.rate(bid.getId(), UID, new ReceptionRatingRequest(4, null)),
                YadonyBusinessException.class);

        assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(e.getErrorCode()).isEqualTo("reception-not-recipient");
        verify(ratingRepository, never()).saveAndFlush(any());
    }

    @Test
    void rate_notDelivered_is409NotAllowed() {
        bid.setStatus(BidStatus.IN_TRANSIT);
        confirmedRecipient(true);

        YadonyBusinessException e = catchThrowableOfType(
                () -> service.rate(bid.getId(), UID, new ReceptionRatingRequest(4, null)),
                YadonyBusinessException.class);

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("reception-rating-not-allowed");
        verify(ratingRepository, never()).saveAndFlush(any());
    }

    @Test
    void rate_alreadyRated_fromAccountOrTrackingLink_is409() {
        confirmedRecipient(true);
        when(ratingRepository.recipientHasRated(bid.getId(), recipientId, TOKEN)).thenReturn(true);

        YadonyBusinessException e = catchThrowableOfType(
                () -> service.rate(bid.getId(), UID, new ReceptionRatingRequest(4, null)),
                YadonyBusinessException.class);

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("reception-already-rated");
        verify(ratingRepository, never()).saveAndFlush(any());
        verify(ratingService, never()).recalculateAverageRating(any());
    }

    @Test
    void rate_concurrentDuplicateHitsUniqueIndex_is409() {
        confirmedRecipient(true);
        when(ratingRepository.recipientHasRated(bid.getId(), recipientId, TOKEN)).thenReturn(false);
        when(ratingRepository.saveAndFlush(any(RatingEntity.class)))
                .thenThrow(new DataIntegrityViolationException("idx_ratings_bid_tracking_token"));

        YadonyBusinessException e = catchThrowableOfType(
                () -> service.rate(bid.getId(), UID, new ReceptionRatingRequest(4, null)),
                YadonyBusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo("reception-already-rated");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rate_unknownBid_is404() {
        UUID unknown = UUID.randomUUID();
        when(linkRepository.existsByBidIdAndRecipientUserIdAndStatus(
                unknown, recipientId, ReceptionLinkStatus.CONFIRMED)).thenReturn(true);
        when(bidRepository.findById(unknown)).thenReturn(Optional.empty());

        YadonyBusinessException e = catchThrowableOfType(
                () -> service.rate(unknown, UID, new ReceptionRatingRequest(4, null)),
                YadonyBusinessException.class);

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.getErrorCode()).isEqualTo("reception-not-found");
    }

    @Test
    void rate_unknownUser_is401() {
        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());

        YadonyBusinessException e = catchThrowableOfType(
                () -> service.rate(bid.getId(), "ghost", new ReceptionRatingRequest(4, null)),
                YadonyBusinessException.class);

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), anyMap());
    }
}
