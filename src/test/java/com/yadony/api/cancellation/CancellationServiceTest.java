package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.dto.CancellationRequest;
import com.yadony.api.cancellation.dto.CancellationResponse;
import com.yadony.api.cancellation.dto.RematchSuggestionDto;
import com.yadony.api.cancellation.events.TravelerHighCancellationEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancellationService — tests unitaires")
class CancellationServiceTest {

    @Mock private CancellationRepository cancellationRepository;
    @Mock private RematchSuggestionRepository rematchSuggestionRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RematchService rematchService;
    @Mock private com.yadony.api.common.StorageService storageService;

    @InjectMocks private CancellationService cancellationService;

    private static final String TRAVELER_UID = "uid-traveler-001";
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UserEntity buildTraveler() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(TRAVELER_UID);
        u.setCancellationCount(0);
        setId(u, TRAVELER_ID);
        return u;
    }

    private AnnouncementEntity buildAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(5));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setStatus(AnnouncementStatus.ACTIVE);
        setId(a, ANNOUNCEMENT_ID);
        return a;
    }

    private BidEntity buildAcceptedBid(UUID senderId) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        b.setSenderId(senderId);
        b.setWeightKg(BigDecimal.valueOf(5));
        b.setStatus(BidStatus.ACCEPTED);
        setId(b, BID_ID);
        return b;
    }

    // ─── cancelTrip ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelTrip()")
    class CancelTripTests {

        @Test
        @DisplayName("annonce active sans bids → annulation réussie + TripCancelledEvent")
        void cancelTrip_activeNoBids_cancelsAndPublishesEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Problème personnel");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
                            BidStatus.NEGOTIATING)))
                    .thenReturn(List.of());
            when(userRepository.save(any())).thenReturn(traveler);

            CancellationResponse result = cancellationService.cancelTrip(TRAVELER_UID, req);

            assertThat(result.announcementId()).isEqualTo(ANNOUNCEMENT_ID);
            assertThat(result.affectedBidsCount()).isEqualTo(0);
            assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.CANCELLED);
            assertThat(traveler.getCancellationCount()).isEqualTo(1);

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            boolean hasTripCancelledEvent = eventCaptor.getAllValues().stream()
                    .anyMatch(e -> e instanceof TripCancelledEvent);
            assertThat(hasTripCancelledEvent).isTrue();
        }

        @Test
        @DisplayName("annonce avec bid ACCEPTED → bid annulé + CancellationEntity créée")
        void cancelTrip_withAcceptedBid_cancelsBidAndCreatesCancellation() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            UUID senderId = UUID.randomUUID();
            BidEntity acceptedBid = buildAcceptedBid(senderId);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Vol annulé");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
                            BidStatus.NEGOTIATING)))
                    .thenReturn(List.of(acceptedBid));
            when(userRepository.save(any())).thenReturn(traveler);
            when(cancellationRepository.save(any(CancellationEntity.class))).thenAnswer(inv -> {
                CancellationEntity c = inv.getArgument(0);
                setId(c, UUID.randomUUID());
                return c;
            });

            CancellationResponse result = cancellationService.cancelTrip(TRAVELER_UID, req);

            assertThat(acceptedBid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            assertThat(result.affectedBidsCount()).isEqualTo(1);
            verify(cancellationRepository).save(any(CancellationEntity.class));
        }

        @Test
        @DisplayName("délègue à RematchService avec les arguments exacts et restitue ses suggestions dans la réponse")
        void cancelTrip_delegatesToRematchServiceAndReturnsItsSuggestions() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            UUID senderId = UUID.randomUUID();
            BidEntity acceptedBid = buildAcceptedBid(senderId);
            List<BidEntity> affectedBidsList = List.of(acceptedBid);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Vol annulé");

            UUID suggestionId = UUID.randomUUID();
            UUID altAnnouncementId = UUID.randomUUID();

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
                            BidStatus.NEGOTIATING)))
                    .thenReturn(affectedBidsList);
            when(userRepository.save(any())).thenReturn(traveler);
            when(cancellationRepository.save(any(CancellationEntity.class))).thenAnswer(inv -> {
                CancellationEntity c = inv.getArgument(0);
                setId(c, UUID.randomUUID());
                return c;
            });

            // RematchService renvoie une map connue référençant la cancellation générée par
            // cancelTrip (récupérée via le 3e argument reçu — on ne peut pas la construire
            // avant l'appel, son id est assigné dans le stub cancellationRepository.save ci-dessus).
            when(rematchService.generateForCancellations(any(), any(), any()))
                    .thenAnswer(inv -> {
                        List<CancellationEntity> cancellations = inv.getArgument(2);
                        CancellationEntity firstCancellation = cancellations.get(0);
                        return Map.of(senderId, new RematchService.RematchInfo(firstCancellation.getId(), 1));
                    });

            AnnouncementEntity altAnnouncement = new AnnouncementEntity();
            setId(altAnnouncement, altAnnouncementId);
            altAnnouncement.setDepartureCity("Paris");
            altAnnouncement.setArrivalCity("Dakar");
            altAnnouncement.setDepartureDate(LocalDate.now().plusDays(3));
            altAnnouncement.setAvailableKg(BigDecimal.TEN);
            altAnnouncement.setPricePerKg(BigDecimal.valueOf(5));

            RematchSuggestionEntity suggestionEntity = new RematchSuggestionEntity();
            setId(suggestionEntity, suggestionId);
            suggestionEntity.setAnnouncementId(altAnnouncementId);

            when(rematchSuggestionRepository.findByCancellationId(any(UUID.class)))
                    .thenReturn(List.of(suggestionEntity));
            when(announcementRepository.findAllById(List.of(altAnnouncementId)))
                    .thenReturn(List.of(altAnnouncement));

            CancellationResponse result = cancellationService.cancelTrip(TRAVELER_UID, req);

            assertThat(result.rematchSuggestions()).hasSize(1);
            RematchSuggestionDto dto = result.rematchSuggestions().get(0);
            assertThat(dto.suggestionId()).isEqualTo(suggestionId);
            assertThat(dto.announcementId()).isEqualTo(altAnnouncementId);
            assertThat(dto.departureCity()).isEqualTo("Paris");
            assertThat(dto.arrivalCity()).isEqualTo("Dakar");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BidEntity>> bidsCaptor = ArgumentCaptor.forClass(List.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<CancellationEntity>> cancellationsCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<AnnouncementEntity> announcementCaptor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            verify(rematchService).generateForCancellations(
                    announcementCaptor.capture(), bidsCaptor.capture(), cancellationsCaptor.capture());

            assertThat(announcementCaptor.getValue()).isSameAs(announcement);
            assertThat(bidsCaptor.getValue()).isSameAs(affectedBidsList);
            assertThat(cancellationsCaptor.getValue()).hasSize(1);
            assertThat(cancellationsCaptor.getValue().get(0).getBidId()).isEqualTo(acceptedBid.getId());
        }

        @Test
        @DisplayName("bids PENDING et PAYMENT_ESCROWED → tous annulés + inclus dans TripCancelledEvent")
        void cancelTrip_pendingAndEscrowedBids_allCancelledAndInEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Vol annulé");

            BidEntity pendingBid = new BidEntity();
            pendingBid.setAnnouncementId(ANNOUNCEMENT_ID);
            pendingBid.setSenderId(UUID.randomUUID());
            pendingBid.setStatus(BidStatus.PENDING);
            pendingBid.setPaymentMethod(com.yadony.api.payments.cash.PaymentMethod.CASH);
            setId(pendingBid, UUID.randomUUID());

            BidEntity escrowedBid = new BidEntity();
            escrowedBid.setAnnouncementId(ANNOUNCEMENT_ID);
            escrowedBid.setSenderId(UUID.randomUUID());
            escrowedBid.setWeightKg(BigDecimal.valueOf(3));
            escrowedBid.setStatus(BidStatus.PAYMENT_ESCROWED);
            setId(escrowedBid, UUID.randomUUID());

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
                            BidStatus.NEGOTIATING)))
                    .thenReturn(List.of(pendingBid, escrowedBid));
            when(userRepository.save(any())).thenReturn(traveler);
            when(cancellationRepository.save(any(CancellationEntity.class))).thenAnswer(inv -> {
                CancellationEntity c = inv.getArgument(0);
                setId(c, UUID.randomUUID());
                return c;
            });

            CancellationResponse result = cancellationService.cancelTrip(TRAVELER_UID, req);

            assertThat(pendingBid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            assertThat(escrowedBid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            assertThat(result.affectedBidsCount()).isEqualTo(2);

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            TripCancelledEvent evt = eventCaptor.getAllValues().stream()
                    .filter(e -> e instanceof TripCancelledEvent)
                    .map(e -> (TripCancelledEvent) e)
                    .findFirst().orElseThrow();
            assertThat(evt.getAffectedBidIds())
                    .containsExactlyInAnyOrder(pendingBid.getId(), escrowedBid.getId());
            // Le paiement par bid est porté par l'event (les listeners refund en dépendent) :
            // cash explicite pour le PENDING, défaut "STRIPE" pour l'escrowed sans méthode.
            assertThat(evt.getBidPaymentMethods())
                    .containsEntry(pendingBid.getId(), "CASH")
                    .containsEntry(escrowedBid.getId(), "STRIPE");
            // Aucune commission prélevée à ce stade → map vide (pas de refund commission).
            assertThat(evt.getBidCommissionChargedVia()).isEmpty();
        }

        @Test
        @DisplayName("3ème annulation → TravelerHighCancellationEvent publié")
        void cancelTrip_thirdCancellation_publishesHighCancellationEvent() {
            UserEntity traveler = buildTraveler();
            traveler.setCancellationCount(2); // This cancellation will make it 3
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Raison");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
                            BidStatus.NEGOTIATING)))
                    .thenReturn(List.of());
            when(userRepository.save(any())).thenReturn(traveler);

            cancellationService.cancelTrip(TRAVELER_UID, req);

            assertThat(traveler.getCancellationCount()).isEqualTo(3);

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeast(2)).publishEvent(eventCaptor.capture());

            boolean hasHighCancellationEvent = eventCaptor.getAllValues().stream()
                    .anyMatch(e -> e instanceof TravelerHighCancellationEvent);
            assertThat(hasHighCancellationEvent).isTrue();
        }

        @Test
        @DisplayName("2 annulations → TravelerHighCancellationEvent NON publié")
        void cancelTrip_secondCancellation_noHighCancellationEvent() {
            UserEntity traveler = buildTraveler();
            traveler.setCancellationCount(1); // Will become 2
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Raison");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
                            BidStatus.NEGOTIATING)))
                    .thenReturn(List.of());
            when(userRepository.save(any())).thenReturn(traveler);

            cancellationService.cancelTrip(TRAVELER_UID, req);

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            boolean hasHighCancellationEvent = eventCaptor.getAllValues().stream()
                    .anyMatch(e -> e instanceof TravelerHighCancellationEvent);
            assertThat(hasHighCancellationEvent).isFalse();
        }

        @Test
        @DisplayName("pas propriétaire de l'annonce → 403 FORBIDDEN")
        void cancelTrip_notOwner_throwsForbidden() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(UUID.randomUUID()); // Different owner
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Raison");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> cancellationService.cancelTrip(TRAVELER_UID, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("annonce déjà annulée → 409 CONFLICT")
        void cancelTrip_alreadyCancelled_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            announcement.setStatus(AnnouncementStatus.CANCELLED);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Raison");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> cancellationService.cancelTrip(TRAVELER_UID, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("already-cancelled"));
        }

        @Test
        @DisplayName("annonce introuvable → 404 NOT_FOUND")
        void cancelTrip_unknownAnnouncement_throwsNotFound() {
            UserEntity traveler = buildTraveler();
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Raison");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cancellationService.cancelTrip(TRAVELER_UID, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("utilisateur introuvable → 404 NOT_FOUND")
        void cancelTrip_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cancellationService.cancelTrip(TRAVELER_UID,
                    new CancellationRequest(ANNOUNCEMENT_ID, "Raison")))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // ─── cancelAnnouncementForDeletedTraveler ──────────────────────────────────
    // Système : suite à la suppression du compte du voyageur (AccountFinalizationService),
    // réutilise le même cœur que cancelTrip (bids annulés + CancellationEntity + rematch +
    // TripCancelledEvent) mais SANS ownership/firebaseUid, et cancelle aussi FULL (pas
    // seulement ACTIVE) puisqu'il n'y a plus personne pour rouvrir la capacité.
    // Contrairement à cancelTrip : pas de pénalité de réputation (compte de toute façon banni).

    @Nested
    @DisplayName("cancelAnnouncementForDeletedTraveler()")
    class CancelAnnouncementForDeletedTravelerTests {

        @Test
        @DisplayName("annonce ACTIVE sans bids → CANCELLED + TripCancelledEvent, pas de pénalité réputation")
        void activeNoBids_cancelsAndPublishesEventWithoutReputationPenalty() {
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
                            BidStatus.NEGOTIATING)))
                    .thenReturn(List.of());

            cancellationService.cancelAnnouncementForDeletedTraveler(ANNOUNCEMENT_ID);

            assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.CANCELLED);
            verify(userRepository, never()).save(any());

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            TripCancelledEvent evt = eventCaptor.getAllValues().stream()
                    .filter(e -> e instanceof TripCancelledEvent)
                    .map(e -> (TripCancelledEvent) e)
                    .findFirst().orElseThrow();
            assertThat(evt.getAnnouncementId()).isEqualTo(ANNOUNCEMENT_ID);
        }

        @Test
        @DisplayName("annonce FULL (pas seulement ACTIVE) → annulée aussi")
        void fullAnnouncement_cancelledToo() {
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            announcement.setStatus(AnnouncementStatus.FULL);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
                            BidStatus.NEGOTIATING)))
                    .thenReturn(List.of());

            cancellationService.cancelAnnouncementForDeletedTraveler(ANNOUNCEMENT_ID);

            assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.CANCELLED);
        }

        @Test
        @DisplayName("bid ACCEPTED → annulé + CancellationEntity avec reason TRAVELER_ACCOUNT_DELETED")
        void withAcceptedBid_cancelsBidAndCreatesCancellationWithAccountDeletedReason() {
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            UUID senderId = UUID.randomUUID();
            BidEntity acceptedBid = buildAcceptedBid(senderId);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
                            BidStatus.NEGOTIATING)))
                    .thenReturn(List.of(acceptedBid));
            when(cancellationRepository.save(any(CancellationEntity.class))).thenAnswer(inv -> {
                CancellationEntity c = inv.getArgument(0);
                setId(c, UUID.randomUUID());
                return c;
            });

            cancellationService.cancelAnnouncementForDeletedTraveler(ANNOUNCEMENT_ID);

            assertThat(acceptedBid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            ArgumentCaptor<CancellationEntity> captor = ArgumentCaptor.forClass(CancellationEntity.class);
            verify(cancellationRepository).save(captor.capture());
            assertThat(captor.getValue().getBidId()).isEqualTo(acceptedBid.getId());
            assertThat(captor.getValue().getReason()).isEqualTo(CancellationReason.TRAVELER_ACCOUNT_DELETED.name());
        }

        @Test
        @DisplayName("annonce déjà CANCELLED → idempotent, no-op")
        void alreadyCancelled_isNoOp() {
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            announcement.setStatus(AnnouncementStatus.CANCELLED);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            cancellationService.cancelAnnouncementForDeletedTraveler(ANNOUNCEMENT_ID);

            verify(announcementRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("annonce introuvable → no-op silencieux (pas d'exception)")
        void unknownAnnouncement_isNoOp() {
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());

            assertThatCode(() -> cancellationService.cancelAnnouncementForDeletedTraveler(ANNOUNCEMENT_ID))
                    .doesNotThrowAnyException();

            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    // ─── getRematchSuggestions ─────────────────────────────────────────────────

    @Nested
    @DisplayName("getRematchSuggestions()")
    class RematchSuggestionsTests {

        @Test
        @DisplayName("annonce non-ACTIVE non-CANCELLED → 409 CONFLICT invalid-status")
        void cancelTrip_nonActiveNonCancelled_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(TRAVELER_ID);
            announcement.setStatus(AnnouncementStatus.FULL);
            CancellationRequest req = new CancellationRequest(ANNOUNCEMENT_ID, "Changement de plan");

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> cancellationService.cancelTrip(TRAVELER_UID, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("cancellation introuvable → 404 NOT_FOUND")
        void getRematchSuggestions_unknownCancellation_throwsNotFound() {
            UUID cancellationId = UUID.randomUUID();
            when(cancellationRepository.findById(cancellationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cancellationService.getRematchSuggestions(cancellationId, "uid"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("caller non-participant → 403 FORBIDDEN")
        void getRematchSuggestions_notParticipant_throwsForbidden() {
            UUID cancellationId = UUID.randomUUID();
            UUID bidId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();

            CancellationEntity cancellation = new CancellationEntity();
            setId(cancellation, cancellationId);
            cancellation.setBidId(bidId);
            cancellation.setCancelledBy(travelerId);

            BidEntity bid = new BidEntity();
            setId(bid, bidId);
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(UUID.randomUUID()); // some other sender

            AnnouncementEntity announcement = new AnnouncementEntity();
            setId(announcement, announcementId);
            announcement.setTravelerId(travelerId);

            UserEntity outsider = new UserEntity();
            setId(outsider, otherId); // not sender, traveler, nor cancelledBy

            when(cancellationRepository.findById(cancellationId)).thenReturn(Optional.of(cancellation));
            when(userRepository.findByFirebaseUid("uid-outsider")).thenReturn(Optional.of(outsider));
            when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> cancellationService.getRematchSuggestions(cancellationId, "uid-outsider"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("suggestions présentes avec annonces → retourne DTOs")
        void getRematchSuggestions_withSuggestions_returnsDtos() {
            UUID cancellationId = UUID.randomUUID();
            UUID bidId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            UUID altAnnouncementId = UUID.randomUUID();
            UUID suggestionId = UUID.randomUUID();
            UUID altTravelerId = UUID.randomUUID();

            CancellationEntity cancellation = new CancellationEntity();
            setId(cancellation, cancellationId);
            cancellation.setBidId(bidId);
            cancellation.setCancelledBy(userId);

            BidEntity bid = new BidEntity();
            setId(bid, bidId);
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(userId);

            AnnouncementEntity announcement = new AnnouncementEntity();
            setId(announcement, announcementId);
            announcement.setTravelerId(travelerId);

            UserEntity caller = new UserEntity();
            setId(caller, userId);

            RematchSuggestionEntity suggestion = new RematchSuggestionEntity();
            setId(suggestion, suggestionId);
            suggestion.setCancellationId(cancellationId);
            suggestion.setAnnouncementId(altAnnouncementId);

            AnnouncementEntity altAnnouncement = new AnnouncementEntity();
            setId(altAnnouncement, altAnnouncementId);
            altAnnouncement.setTravelerId(altTravelerId);
            altAnnouncement.setDepartureCity("Paris");
            altAnnouncement.setArrivalCity("Dakar");
            altAnnouncement.setDepartureDate(LocalDate.now().plusDays(7));
            altAnnouncement.setAvailableKg(BigDecimal.TEN);
            altAnnouncement.setPricePerKg(BigDecimal.valueOf(5));

            UserEntity altTraveler = new UserEntity();
            setId(altTraveler, altTravelerId);
            altTraveler.setFirstName("Moussa");
            altTraveler.setAverageRating(BigDecimal.valueOf(4.8));
            altTraveler.setRatingCount(12);
            altTraveler.setAvatarUrl("users/avatar-key.jpg");

            when(cancellationRepository.findById(cancellationId)).thenReturn(Optional.of(cancellation));
            when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(caller));
            when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
            when(rematchSuggestionRepository.findByCancellationId(cancellationId)).thenReturn(List.of(suggestion));
            when(announcementRepository.findAllById(List.of(altAnnouncementId)))
                    .thenReturn(List.of(altAnnouncement));
            when(userRepository.findAllById(List.of(altTravelerId))).thenReturn(List.of(altTraveler));
            when(storageService.avatarUrl("users/avatar-key.jpg"))
                    .thenReturn("https://s3.example/presigned-avatar");

            List<RematchSuggestionDto> result = cancellationService.getRematchSuggestions(cancellationId, "uid");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).departureCity()).isEqualTo("Paris");
            assertThat(result.get(0).travelerFirstName()).isEqualTo("Moussa");
            assertThat(result.get(0).travelerRating()).isEqualByComparingTo(BigDecimal.valueOf(4.8));
            assertThat(result.get(0).travelerRatingCount()).isEqualTo(12);
            assertThat(result.get(0).travelerAvatarUrl()).isEqualTo("https://s3.example/presigned-avatar");
        }

        @Test
        @DisplayName("2 suggestions / 2 voyageurs distincts → un seul findAllById par repository (anti N+1)")
        void getRematchSuggestions_twoSuggestionsTwoTravelers_batchesAnnouncementsAndTravelersInOneCallEach() {
            UUID cancellationId = UUID.randomUUID();
            UUID bidId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();

            CancellationEntity cancellation = new CancellationEntity();
            setId(cancellation, cancellationId);
            cancellation.setBidId(bidId);
            cancellation.setCancelledBy(userId);

            BidEntity bid = new BidEntity();
            setId(bid, bidId);
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(userId);

            AnnouncementEntity announcement = new AnnouncementEntity();
            setId(announcement, announcementId);
            announcement.setTravelerId(travelerId);

            UserEntity caller = new UserEntity();
            setId(caller, userId);

            UUID altAnnouncementId1 = UUID.randomUUID();
            UUID altAnnouncementId2 = UUID.randomUUID();
            UUID altTravelerId1 = UUID.randomUUID();
            UUID altTravelerId2 = UUID.randomUUID();
            UUID suggestionId1 = UUID.randomUUID();
            UUID suggestionId2 = UUID.randomUUID();

            RematchSuggestionEntity suggestion1 = new RematchSuggestionEntity();
            setId(suggestion1, suggestionId1);
            suggestion1.setCancellationId(cancellationId);
            suggestion1.setAnnouncementId(altAnnouncementId1);

            RematchSuggestionEntity suggestion2 = new RematchSuggestionEntity();
            setId(suggestion2, suggestionId2);
            suggestion2.setCancellationId(cancellationId);
            suggestion2.setAnnouncementId(altAnnouncementId2);

            AnnouncementEntity altAnnouncement1 = new AnnouncementEntity();
            setId(altAnnouncement1, altAnnouncementId1);
            altAnnouncement1.setTravelerId(altTravelerId1);
            altAnnouncement1.setDepartureCity("Paris");
            altAnnouncement1.setArrivalCity("Dakar");
            altAnnouncement1.setDepartureDate(LocalDate.now().plusDays(3));
            altAnnouncement1.setAvailableKg(BigDecimal.TEN);
            altAnnouncement1.setPricePerKg(BigDecimal.valueOf(5));

            AnnouncementEntity altAnnouncement2 = new AnnouncementEntity();
            setId(altAnnouncement2, altAnnouncementId2);
            altAnnouncement2.setTravelerId(altTravelerId2);
            altAnnouncement2.setDepartureCity("Lyon");
            altAnnouncement2.setArrivalCity("Abidjan");
            altAnnouncement2.setDepartureDate(LocalDate.now().plusDays(4));
            altAnnouncement2.setAvailableKg(BigDecimal.valueOf(15));
            altAnnouncement2.setPricePerKg(BigDecimal.valueOf(6));

            UserEntity altTraveler1 = new UserEntity();
            setId(altTraveler1, altTravelerId1);
            altTraveler1.setFirstName("Moussa");

            UserEntity altTraveler2 = new UserEntity();
            setId(altTraveler2, altTravelerId2);
            altTraveler2.setFirstName("Fatou");

            when(cancellationRepository.findById(cancellationId)).thenReturn(Optional.of(cancellation));
            when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(caller));
            when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
            when(rematchSuggestionRepository.findByCancellationId(cancellationId))
                    .thenReturn(List.of(suggestion1, suggestion2));
            // L'ordre d'itération d'un Map (HashMap via Collectors.toMap) n'est pas garanti —
            // on matche sur any() plutôt que sur une liste précisément ordonnée.
            when(announcementRepository.findAllById(any()))
                    .thenReturn(List.of(altAnnouncement1, altAnnouncement2));
            when(userRepository.findAllById(any()))
                    .thenReturn(List.of(altTraveler1, altTraveler2));

            List<RematchSuggestionDto> result = cancellationService.getRematchSuggestions(cancellationId, "uid");

            assertThat(result).hasSize(2);
            assertThat(result).extracting(RematchSuggestionDto::travelerFirstName)
                    .containsExactlyInAnyOrder("Moussa", "Fatou");

            // Anti N+1 : un seul findAllById pour les annonces, un seul pour les voyageurs —
            // jamais un findById par suggestion.
            verify(announcementRepository, times(1)).findAllById(any());
            verify(userRepository, times(1)).findAllById(any());
            verify(announcementRepository, never()).findById(altAnnouncementId1);
            verify(announcementRepository, never()).findById(altAnnouncementId2);
        }

        @Test
        @DisplayName("cancellation valide, caller participant, sans suggestions → liste vide")
        void getRematchSuggestions_noSuggestions_returnsEmpty() {
            UUID cancellationId = UUID.randomUUID();
            UUID bidId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();

            CancellationEntity cancellation = new CancellationEntity();
            setId(cancellation, cancellationId);
            cancellation.setBidId(bidId);
            cancellation.setCancelledBy(travelerId);

            BidEntity bid = new BidEntity();
            setId(bid, bidId);
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(userId);

            AnnouncementEntity announcement = new AnnouncementEntity();
            setId(announcement, announcementId);
            announcement.setTravelerId(travelerId);

            UserEntity caller = new UserEntity();
            setId(caller, userId);
            caller.setFirebaseUid("uid");

            when(cancellationRepository.findById(cancellationId)).thenReturn(Optional.of(cancellation));
            when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(caller));
            when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
            when(rematchSuggestionRepository.findByCancellationId(cancellationId)).thenReturn(List.of());

            var result = cancellationService.getRematchSuggestions(cancellationId, "uid");

            assertThat(result).isEmpty();
        }
    }
}
