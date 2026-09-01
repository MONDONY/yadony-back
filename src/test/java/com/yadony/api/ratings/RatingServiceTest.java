package com.yadony.api.ratings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.ratings.dto.PendingRatingResponse;
import com.yadony.api.ratings.dto.RatingRequest;
import com.yadony.api.ratings.dto.RatingResponse;
import com.yadony.api.ratings.dto.RecipientRatingRequest;
import com.yadony.api.ratings.dto.TravelerRatingRequest;
import com.yadony.api.ratings.dto.UserRatingsSummaryResponse;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.yadony.api.ratings.events.RatingCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RatingService — tests unitaires")
class RatingServiceTest {

    @Mock private RatingRepository ratingRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private CancellationRepository cancellationRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private BlockVisibility blockVisibility;

    @InjectMocks private RatingService ratingService;

    private static final String SENDER_UID = "uid-sender-001";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID VIEWER_ID = UUID.randomUUID();
    private static final String TRACKING_TOKEN = "tok-abc-123";

    private UserEntity sender;
    private BidEntity bid;
    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() throws Exception {
        sender = new UserEntity();
        setId(sender, SENDER_ID);
        setField(sender, "firebaseUid", SENDER_UID);

        bid = new BidEntity();
        setId(bid, BID_ID);
        setField(bid, "senderId", SENDER_ID);
        setField(bid, "announcementId", ANNOUNCEMENT_ID);
        setField(bid, "status", BidStatus.COMPLETED);
        setField(bid, "trackingToken", TRACKING_TOKEN);
        setField(bid, "updatedAt", LocalDateTime.now().minusDays(1));

        announcement = new AnnouncementEntity();
        setId(announcement, ANNOUNCEMENT_ID);
        setField(announcement, "travelerId", TRAVELER_ID);
    }

    @Nested
    @DisplayName("createRating() — expéditeur")
    class CreateRatingTests {

        @Test
        @DisplayName("notation valide → persist + recalcul + event publié")
        void createRating_valid_persistsAndPublishesEvent() {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(ratingRepository.existsByBidIdAndRaterId(BID_ID, SENDER_ID)).thenReturn(false);
            when(ratingRepository.save(any(RatingEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID)).thenReturn(List.of());

            RatingRequest request = new RatingRequest(BID_ID, 4, "Super voyageur");
            ratingService.createRating(SENDER_UID, request);

            verify(ratingRepository).save(any(RatingEntity.class));
            ArgumentCaptor<RatingCreatedEvent> eventCaptor = ArgumentCaptor.forClass(RatingCreatedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getRatedUserId()).isEqualTo(TRAVELER_ID);
            assertThat(eventCaptor.getValue().getStars()).isEqualTo(4);
        }

        @Test
        @DisplayName("bid non livré → 422 UNPROCESSABLE_ENTITY")
        void createRating_bidNotDelivered_throwsException() throws Exception {
            setField(bid, "status", BidStatus.ACCEPTED);
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            assertThatThrownBy(() -> ratingService.createRating(SENDER_UID, new RatingRequest(BID_ID, 5, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }

        @Test
        @DisplayName("fenêtre expirée → 422 UNPROCESSABLE_ENTITY")
        void createRating_windowExpired_throwsException() throws Exception {
            setField(bid, "updatedAt", LocalDateTime.now().minusDays(10));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            assertThatThrownBy(() -> ratingService.createRating(SENDER_UID, new RatingRequest(BID_ID, 5, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("rating-window-expired"));
        }

        @Test
        @DisplayName("notation déjà envoyée → 409 CONFLICT")
        void createRating_alreadyRated_throwsConflict() {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(ratingRepository.existsByBidIdAndRaterId(BID_ID, SENDER_ID)).thenReturn(true);

            assertThatThrownBy(() -> ratingService.createRating(SENDER_UID, new RatingRequest(BID_ID, 5, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("expéditeur non propriétaire → 403 FORBIDDEN")
        void createRating_wrongSender_throwsForbidden() throws Exception {
            setField(bid, "senderId", UUID.randomUUID());
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            assertThatThrownBy(() -> ratingService.createRating(SENDER_UID, new RatingRequest(BID_ID, 5, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("bid annulé après remise mais colis restitué (returnedAt≠null) → notation autorisée (D5)")
        void createRating_cancelledButReturned_persists() throws Exception {
            setField(bid, "status", BidStatus.CANCELLED);
            setField(bid, "returnedAt", LocalDateTime.now().minusHours(2));
            setField(bid, "updatedAt", LocalDateTime.now().minusHours(2));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(ratingRepository.existsByBidIdAndRaterId(BID_ID, SENDER_ID)).thenReturn(false);
            when(ratingRepository.save(any(RatingEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID)).thenReturn(List.of());

            ratingService.createRating(SENDER_UID, new RatingRequest(BID_ID, 3, "Colis rendu"));

            verify(ratingRepository).save(any(RatingEntity.class));
            verify(eventPublisher).publishEvent(any(RatingCreatedEvent.class));
        }
    }

    @Nested
    @DisplayName("createRecipientRating() — destinataire sans compte")
    class RecipientRatingTests {

        @Test
        @DisplayName("token valide + livraison confirmée → persist + recalcul + event")
        void createRecipientRating_valid_persistsAndPublishesEvent() {
            when(bidRepository.findByTrackingToken(TRACKING_TOKEN)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(ratingRepository.existsByBidIdAndTrackingToken(BID_ID, TRACKING_TOKEN)).thenReturn(false);
            when(ratingRepository.save(any(RatingEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID)).thenReturn(List.of());

            ratingService.createRecipientRating(new RecipientRatingRequest(TRACKING_TOKEN, 5, null));

            verify(ratingRepository).save(any(RatingEntity.class));
            verify(eventPublisher).publishEvent(any(RatingCreatedEvent.class));
        }

        @Test
        @DisplayName("token invalide → 404 NOT_FOUND")
        void createRecipientRating_invalidToken_throws404() {
            when(bidRepository.findByTrackingToken("bad-token")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ratingService.createRecipientRating(
                    new RecipientRatingRequest("bad-token", 3, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("déjà noté → 409 CONFLICT")
        void createRecipientRating_alreadyRated_throwsConflict() {
            when(bidRepository.findByTrackingToken(TRACKING_TOKEN)).thenReturn(Optional.of(bid));
            when(ratingRepository.existsByBidIdAndTrackingToken(BID_ID, TRACKING_TOKEN)).thenReturn(true);

            assertThatThrownBy(() -> ratingService.createRecipientRating(
                    new RecipientRatingRequest(TRACKING_TOKEN, 5, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }
    }

    @Nested
    @DisplayName("recalculateAverageRating()")
    class RecalculateTests {

        @Test
        @DisplayName("plusieurs notations → moyenne + ratingCount correctement calculés")
        void recalculate_multipleRatings_computesAverageAndCount() throws Exception {
            RatingEntity r1 = buildRating(4);
            RatingEntity r2 = buildRating(5);
            RatingEntity r3 = buildRating(3);
            UserEntity traveler = new UserEntity();
            setId(traveler, TRAVELER_ID);

            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID))
                    .thenReturn(List.of(r1, r2, r3));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.save(any())).thenReturn(traveler);

            ratingService.recalculateAverageRating(TRAVELER_ID);

            assertThat(traveler.getAverageRating())
                    .isEqualByComparingTo(new BigDecimal("4.00"));
            assertThat(traveler.getRatingCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("aucune notation → ratingCount remis à 0, averageRating inchangée")
        void recalculate_noRatings_setsCountToZero() throws Exception {
            UserEntity traveler = new UserEntity();
            setId(traveler, TRAVELER_ID);

            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.save(any())).thenReturn(traveler);

            ratingService.recalculateAverageRating(TRAVELER_ID);

            assertThat(traveler.getRatingCount()).isEqualTo(0);
            assertThat(traveler.getAverageRating()).isNull();
            verify(userRepository).save(traveler);
        }
    }

    @Nested
    @DisplayName("createTravelerRating() — voyageur note l'expéditeur")
    class CreateTravelerRatingTests {

        private static final String TRAVELER_UID = "uid-traveler-001";
        private UserEntity traveler;

        @BeforeEach
        void setUpTraveler() throws Exception {
            traveler = new UserEntity();
            setId(traveler, TRAVELER_ID);
            setField(traveler, "firebaseUid", TRAVELER_UID);
            setField(traveler, "firstName", "Moussa");
            setField(traveler, "lastName", "Diallo");
        }

        @Test
        @DisplayName("notation valide → persist + recalcul sur expéditeur + event publié")
        void createTravelerRating_valid_persistsAndPublishesEvent() {
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(ratingRepository.existsByBidIdAndRaterId(BID_ID, TRAVELER_ID)).thenReturn(false);
            when(ratingRepository.save(any(RatingEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(ratingRepository.findIncludedRatingsByRatedUserId(SENDER_ID)).thenReturn(List.of());

            var request = new TravelerRatingRequest(BID_ID, 5, "Expéditeur sérieux");
            RatingResponse response = ratingService.createTravelerRating(TRAVELER_UID, request);

            ArgumentCaptor<RatingEntity> captor = ArgumentCaptor.forClass(RatingEntity.class);
            verify(ratingRepository).save(captor.capture());
            assertThat(captor.getValue().getRatedUserId()).isEqualTo(SENDER_ID);
            assertThat(captor.getValue().getRaterId()).isEqualTo(TRAVELER_ID);
            assertThat(captor.getValue().getStars()).isEqualTo(5);

            ArgumentCaptor<RatingCreatedEvent> eventCaptor = ArgumentCaptor.forClass(RatingCreatedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getRatedUserId()).isEqualTo(SENDER_ID);
        }

        @Test
        @DisplayName("caller n'est pas le voyageur → 403 FORBIDDEN")
        void createTravelerRating_wrongTraveler_throwsForbidden() throws Exception {
            setField(announcement, "travelerId", UUID.randomUUID()); // autre voyageur
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> ratingService.createTravelerRating(TRAVELER_UID,
                    new TravelerRatingRequest(BID_ID, 4, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("bid non livré → 422 UNPROCESSABLE_ENTITY")
        void createTravelerRating_bidNotDelivered_throws422() throws Exception {
            setField(bid, "status", BidStatus.ACCEPTED);
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> ratingService.createTravelerRating(TRAVELER_UID,
                    new TravelerRatingRequest(BID_ID, 5, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("bid-not-delivered"));
        }

        @Test
        @DisplayName("fenêtre expirée → 422 rating-window-expired")
        void createTravelerRating_windowExpired_throws422() throws Exception {
            setField(bid, "updatedAt", LocalDateTime.now().minusDays(10));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> ratingService.createTravelerRating(TRAVELER_UID,
                    new TravelerRatingRequest(BID_ID, 3, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("rating-window-expired"));
        }

        @Test
        @DisplayName("déjà noté → 409 CONFLICT")
        void createTravelerRating_alreadyRated_throws409() {
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(ratingRepository.existsByBidIdAndRaterId(BID_ID, TRAVELER_ID)).thenReturn(true);

            assertThatThrownBy(() -> ratingService.createTravelerRating(TRAVELER_UID,
                    new TravelerRatingRequest(BID_ID, 5, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("no-show expéditeur CONFIRMED → voyageur autorisé à noter (D6)")
        void createTravelerRating_senderNoShowConfirmed_persists() throws Exception {
            setField(bid, "status", BidStatus.CANCELLED);
            setField(bid, "updatedAt", LocalDateTime.now().minusHours(3));
            CancellationEntity c = new CancellationEntity();
            c.setReason(CancellationReason.SENDER_NO_SHOW.name());
            c.setNoShowStatus(CancellationStatus.CONFIRMED);
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));
            when(ratingRepository.existsByBidIdAndRaterId(BID_ID, TRAVELER_ID)).thenReturn(false);
            when(ratingRepository.save(any(RatingEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(ratingRepository.findIncludedRatingsByRatedUserId(SENDER_ID)).thenReturn(List.of());

            ratingService.createTravelerRating(TRAVELER_UID,
                    new TravelerRatingRequest(BID_ID, 1, "Absent à la remise"));

            verify(ratingRepository).save(any(RatingEntity.class));
            verify(eventPublisher).publishEvent(any(RatingCreatedEvent.class));
        }

        @Test
        @DisplayName("no-show expéditeur PENDING (pas encore confirmé) → 422 (D8 anti-farming)")
        void createTravelerRating_senderNoShowPending_throws422() throws Exception {
            setField(bid, "status", BidStatus.CANCELLED);
            CancellationEntity c = new CancellationEntity();
            c.setReason(CancellationReason.SENDER_NO_SHOW.name());
            c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> ratingService.createTravelerRating(TRAVELER_UID,
                    new TravelerRatingRequest(BID_ID, 2, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("bid-not-delivered"));
        }

        @Test
        @DisplayName("annulation pour autre motif (pas no-show) → 422 — pas de droit de notation")
        void createTravelerRating_otherCancellationReason_throws422() throws Exception {
            setField(bid, "status", BidStatus.CANCELLED);
            CancellationEntity c = new CancellationEntity();
            c.setReason(CancellationReason.TRAVELER_CANCEL_AFTER_HANDOVER.name());
            c.setNoShowStatus(CancellationStatus.CONFIRMED);
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> ratingService.createTravelerRating(TRAVELER_UID,
                    new TravelerRatingRequest(BID_ID, 2, null)))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("bid-not-delivered"));
        }
    }

    @Nested
    @DisplayName("getUserRatings()")
    class GetUserRatingsTests {

        @Test
        @DisplayName("utilisateur avec 3 notations → résumé correct")
        void getUserRatings_withRatings_returnsCorrectSummary() throws Exception {
            UserEntity user = new UserEntity();
            setId(user, TRAVELER_ID);
            setField(user, "averageRating", new BigDecimal("4.33"));

            RatingEntity r1 = buildRating(5); r1.setComment("Top !");
            RatingEntity r2 = buildRating(4); r2.setComment(null);
            RatingEntity r3 = buildRating(4); r3.setComment("Bien");

            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(user));
            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID))
                    .thenReturn(List.of(r1, r2, r3));
            when(ratingRepository.findByRatedUserId(eq(TRAVELER_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(r1, r2, r3)));

            UserRatingsSummaryResponse response = ratingService.getUserRatings(TRAVELER_ID, 0, 20, VIEWER_ID);

            assertThat(response.ratingCount()).isEqualTo(3);
            assertThat(response.averageRating()).isEqualByComparingTo(new BigDecimal("4.33"));
            assertThat(response.distribution().get(5)).isEqualTo(1L);
            assertThat(response.distribution().get(4)).isEqualTo(2L);
            assertThat(response.distribution().get(1)).isEqualTo(0L);
            assertThat(response.ratings()).hasSize(3);
        }

        @Test
        @DisplayName("utilisateur masqué par un blocage → 404 sans lecture des notes")
        void getUserRatings_hiddenByBlock_throws404() {
            doThrow(new YadonyBusinessException(
                    HttpStatus.NOT_FOUND, "not-found", "Not Found", "Ressource introuvable"))
                    .when(blockVisibility).assertVisible(VIEWER_ID, TRAVELER_ID);

            assertThatThrownBy(() -> ratingService.getUserRatings(TRAVELER_ID, 0, 20, VIEWER_ID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        // 404 et non 403 : le blocage doit rester indétectable.
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.getErrorCode()).isEqualTo("not-found");
                    });

            verifyNoInteractions(ratingRepository);
        }

        @Test
        @DisplayName("viewer anonyme → aucun masquage, notes rendues")
        void getUserRatings_anonymousViewer_returnsSummary() throws Exception {
            UserEntity user = new UserEntity();
            setId(user, TRAVELER_ID);
            setField(user, "averageRating", new BigDecimal("5.00"));

            RatingEntity r = buildRating(5);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(user));
            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID)).thenReturn(List.of(r));
            when(ratingRepository.findByRatedUserId(eq(TRAVELER_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(r)));

            UserRatingsSummaryResponse res = ratingService.getUserRatings(TRAVELER_ID, 0, 20, null);

            assertThat(res.ratingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("note écrite par un compte bloqué → conservée, moyenne inchangée")
        void getUserRatings_ratingWrittenByBlockedUser_isKept() throws Exception {
            // Décision produit assumée : on masque le profil, on ne réécrit pas l'historique
            // de réputation. Un avis laissé par un compte depuis bloqué reste affiché et
            // continue de compter — sinon la moyenne d'un tiers changerait selon qui regarde.
            UUID blockedAuthorId = UUID.randomUUID();

            UserEntity ratedUser = new UserEntity();
            setId(ratedUser, TRAVELER_ID);
            setField(ratedUser, "averageRating", new BigDecimal("5.00"));

            UserEntity blockedAuthor = new UserEntity();
            setId(blockedAuthor, blockedAuthorId);
            setField(blockedAuthor, "firstName", "Fatou");
            setField(blockedAuthor, "lastName", "Mbaye");

            RatingEntity r = buildRating(5);
            r.setRaterId(blockedAuthorId);

            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(ratedUser));
            when(userRepository.findById(blockedAuthorId)).thenReturn(Optional.of(blockedAuthor));
            when(ratingRepository.findIncludedRatingsByRatedUserId(TRAVELER_ID)).thenReturn(List.of(r));
            when(ratingRepository.findByRatedUserId(eq(TRAVELER_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(r)));

            UserRatingsSummaryResponse res = ratingService.getUserRatings(TRAVELER_ID, 0, 20, VIEWER_ID);

            assertThat(res.ratings()).hasSize(1);
            assertThat(res.ratings().get(0).authorName()).isEqualTo("Fatou M.");
            assertThat(res.ratingCount()).isEqualTo(1);
            assertThat(res.averageRating()).isEqualByComparingTo(new BigDecimal("5.00"));
            // Aucun filtrage d'auteur : la liste des bloqués n'est même pas consultée.
            verify(blockVisibility, never()).hiddenUserIdsFor(any());
        }

        @Test
        @DisplayName("utilisateur inexistant → 404")
        void getUserRatings_unknownUser_throws404() {
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ratingService.getUserRatings(TRAVELER_ID, 0, 20, VIEWER_ID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("notation avec auteur + corridor → champs enrichis correctement mappés")
        void getUserRatings_enrichesAuthorAndCorridor() throws Exception {
            UUID ratedUserId = UUID.randomUUID();
            UUID raterId = UUID.randomUUID();
            UUID bidId = UUID.randomUUID();
            UUID annId = UUID.randomUUID();

            UserEntity ratedUser = new UserEntity();
            setId(ratedUser, ratedUserId);
            setField(ratedUser, "averageRating", new BigDecimal("5.00"));

            UserEntity rater = new UserEntity();
            setId(rater, raterId);
            setField(rater, "firstName", "Fatou");
            setField(rater, "lastName", "Mbaye");
            setField(rater, "avatarUrl", "https://cdn/f.jpg");

            RatingEntity rating = new RatingEntity();
            rating.setStars(5);
            rating.setRatedUserId(ratedUserId);
            rating.setBidId(bidId);
            rating.setRaterId(raterId);

            BidEntity bidEntity = new BidEntity();
            setId(bidEntity, bidId);
            setField(bidEntity, "announcementId", annId);

            AnnouncementEntity ann = new AnnouncementEntity();
            setId(ann, annId);
            setField(ann, "departureCity", "Paris");
            setField(ann, "arrivalCity", "Dakar");
            setField(ann, "travelerId", UUID.randomUUID());

            when(userRepository.findById(ratedUserId)).thenReturn(Optional.of(ratedUser));
            when(ratingRepository.findIncludedRatingsByRatedUserId(ratedUserId))
                    .thenReturn(List.of(rating));
            when(ratingRepository.findByRatedUserId(eq(ratedUserId), any()))
                    .thenReturn(new PageImpl<>(List.of(rating)));
            when(userRepository.findById(raterId)).thenReturn(Optional.of(rater));
            when(bidRepository.findById(bidId)).thenReturn(Optional.of(bidEntity));
            when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));

            UserRatingsSummaryResponse res = ratingService.getUserRatings(ratedUserId, 0, 20, VIEWER_ID);

            assertThat(res.ratings()).hasSize(1);
            var item = res.ratings().get(0);
            assertThat(item.authorName()).isEqualTo("Fatou M.");
            assertThat(item.authorAvatarUrl()).isEqualTo("https://cdn/f.jpg");
            assertThat(item.departureCity()).isEqualTo("Paris");
            assertThat(item.arrivalCity()).isEqualTo("Dakar");
        }

        @Test
        @DisplayName("notation anonyme (raterId=null) → authorName null, corridor null si bid absent")
        void getUserRatings_anonymousRater_authorNullCorridorNull() throws Exception {
            UUID ratedUserId = UUID.randomUUID();
            UUID bidId = UUID.randomUUID();

            UserEntity ratedUser = new UserEntity();
            setId(ratedUser, ratedUserId);

            RatingEntity rating = new RatingEntity();
            rating.setStars(4);
            rating.setRatedUserId(ratedUserId);
            rating.setBidId(bidId);
            // raterId left null (anonymous recipient rating)

            when(userRepository.findById(ratedUserId)).thenReturn(Optional.of(ratedUser));
            when(ratingRepository.findIncludedRatingsByRatedUserId(ratedUserId)).thenReturn(List.of());
            when(ratingRepository.findByRatedUserId(eq(ratedUserId), any()))
                    .thenReturn(new PageImpl<>(List.of(rating)));
            when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

            UserRatingsSummaryResponse res = ratingService.getUserRatings(ratedUserId, 0, 20, VIEWER_ID);

            assertThat(res.ratings()).hasSize(1);
            var item = res.ratings().get(0);
            assertThat(item.authorName()).isNull();
            assertThat(item.authorAvatarUrl()).isNull();
            assertThat(item.departureCity()).isNull();
            assertThat(item.arrivalCity()).isNull();
        }
    }

    @Nested
    @DisplayName("getPendingRating()")
    class GetPendingRatingTests {

        @Test
        @DisplayName("bid en attente → retourne PendingRatingResponse avec isTravelerRating=false pour expéditeur")
        void getPendingRating_senderPending_returnsResponse() throws Exception {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findPendingRatingForUser(SENDER_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            UserEntity traveler = new UserEntity();
            setId(traveler, TRAVELER_ID);
            setField(traveler, "firstName", "Moussa");
            setField(traveler, "lastName", "Diallo");
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            Optional<PendingRatingResponse> result = ratingService.getPendingRating(SENDER_UID);

            assertThat(result).isPresent();
            assertThat(result.get().bidId()).isEqualTo(BID_ID);
            assertThat(result.get().otherPartyId()).isEqualTo(TRAVELER_ID);
            assertThat(result.get().isTravelerRating()).isFalse();
            assertThat(result.get().otherPartyName()).contains("Moussa");
        }

        @Test
        @DisplayName("aucun bid en attente → Optional.empty()")
        void getPendingRating_noPending_returnsEmpty() {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.findPendingRatingForUser(SENDER_ID)).thenReturn(Optional.empty());

            Optional<PendingRatingResponse> result = ratingService.getPendingRating(SENDER_UID);
            assertThat(result).isEmpty();
        }
    }

    // ─── getMyReceivedRatings() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getMyReceivedRatings() — notes reçues du connecté")
    class GetMyReceivedRatingsTests {

        @Test
        @DisplayName("uid inconnu → 401 UNAUTHORIZED")
        void getMyReceivedRatings_unknownUid_throws401() {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ratingService.getMyReceivedRatings(SENDER_UID, 0, 20))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.UNAUTHORIZED));
        }

        @Test
        @DisplayName("uid valide → délègue à getUserRatings avec l'UUID de l'utilisateur")
        void getMyReceivedRatings_validUid_delegatesToGetUserRatings() throws Exception {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            RatingEntity r = buildRating(5);
            when(ratingRepository.findByRatedUserId(eq(SENDER_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(r)));
            when(ratingRepository.findIncludedRatingsByRatedUserId(SENDER_ID)).thenReturn(List.of(r));

            var result = ratingService.getMyReceivedRatings(SENDER_UID, 0, 20);

            assertThat(result).isNotNull();
            assertThat(result.ratingCount()).isEqualTo(1);
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private RatingEntity buildRating(int stars) {
        RatingEntity r = new RatingEntity();
        r.setStars(stars);
        r.setRatedUserId(TRAVELER_ID);
        r.setBidId(BID_ID);
        return r;
    }

    private static void setId(Object obj, UUID id) throws Exception {
        Field f = obj.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(obj, id);
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f;
        try {
            f = obj.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = obj.getClass().getSuperclass().getDeclaredField(name);
        }
        f.setAccessible(true);
        f.set(obj, value);
    }
}
