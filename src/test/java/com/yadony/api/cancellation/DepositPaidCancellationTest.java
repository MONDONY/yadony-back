package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.dto.CancellationRequest;
import com.yadony.api.cancellation.events.TripCancelRefusedDepositPaidEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.PayoutTrigger;
import com.yadony.api.payments.cash.CommissionProperties;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Aucun remboursement promis après un versement au dépôt : annulation après remise et annulation du trajet refusées en
 * 409 {@code deposit-paid-no-refund} quand la plateforme a RÉELLEMENT payé le transporteur au dépôt ; inchangées sinon
 * (colis payé à la livraison, espèces, versement pas encore parti).
 */
@ExtendWith(MockitoExtension.class)
class DepositPaidCancellationTest {

    private static final String FIREBASE = "fb-uid";
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANN_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @Mock private CancellationRepository cancellationRepository;
    @Mock private RematchSuggestionRepository rematchSuggestionRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RematchService rematchService;
    @Mock private com.yadony.api.common.StorageService storageService;
    @Mock private DeliveryNoShowProcedureService deliveryNoShowProcedure;
    @Mock private NegotiationThreadRepository threads;

    private CancellationService service;
    private final List<UUID> askedPort = new ArrayList<>();
    private boolean portAnswer;

    @BeforeEach
    void setUp() {
        service = new CancellationService(cancellationRepository, rematchSuggestionRepository, bidRepository,
                announcementRepository, userRepository, auditService, eventPublisher,
                new CommissionProperties(new BigDecimal("0.12"), new BigDecimal("1.00"), 24),
                rematchService, storageService, deliveryNoShowProcedure, threads);
        service.setDepositPayoutPort(bidId -> {
            askedPort.add(bidId);
            return portAnswer;
        });
    }

    // ── CancellationGuard.depositPaid ──────────────────────────────────────

    @Nested
    class Guard {

        @Test
        void deliveryBid_neverAsksPayments() {
            assertThat(CancellationGuard.depositPaid(bid(BidStatus.HANDED_OVER, PayoutTrigger.DELIVERY), id -> true))
                    .isFalse();
        }

        @Test
        void depositBidNotYetDeposited_false() {
            assertThat(CancellationGuard.depositPaid(bid(BidStatus.ACCEPTED, PayoutTrigger.DEPOSIT), id -> true))
                    .isFalse();
        }

        @Test
        void depositBidDeposited_followsRealPayout() {
            for (BidStatus status : List.of(BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED,
                    BidStatus.COMPLETED)) {
                assertThat(CancellationGuard.depositPaid(bid(status, PayoutTrigger.DEPOSIT), id -> true)).isTrue();
                assertThat(CancellationGuard.depositPaid(bid(status, PayoutTrigger.DEPOSIT), id -> false)).isFalse();
            }
        }

        @Test
        void missingPort_assumesPaid() {
            assertThat(CancellationGuard.depositPaid(bid(BidStatus.HANDED_OVER, PayoutTrigger.DEPOSIT), null)).isTrue();
            assertThat(CancellationGuard.depositPaid(null, id -> true)).isFalse();
        }

        @Test
        void assertNoRefund_409WithLocalizedCode() {
            assertThatThrownBy(() -> CancellationGuard.assertNoRefundAfterDepositPayout(
                    bid(BidStatus.HANDED_OVER, PayoutTrigger.DEPOSIT), id -> true))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getErrorCode()).isEqualTo("deposit-paid-no-refund");
                    });
        }
    }

    // ── Annulation après remise ────────────────────────────────────────────

    @Test
    void cancelAfterHandover_depositPaid_409() {
        portAnswer = true;
        when(userRepository.findByFirebaseUid(FIREBASE)).thenReturn(Optional.of(user(SENDER_ID)));
        when(bidRepository.findByIdForUpdate(BID_ID))
                .thenReturn(Optional.of(bid(BidStatus.HANDED_OVER, PayoutTrigger.DEPOSIT)));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(trip()));

        assertThatThrownBy(() -> service.cancelAfterHandover(FIREBASE, BID_ID))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("deposit-paid-no-refund"));

        assertThat(askedPort).containsExactly(BID_ID);
        verify(cancellationRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelAfterHandover_depositNotPaid_refundsAsToday() {
        // Colis payé au dépôt, déposé, mais versement pas parti (espèces, ou Connect pas prêt) : annulation inchangée.
        portAnswer = false;
        stubCancelAfterHandover(bid(BidStatus.HANDED_OVER, PayoutTrigger.DEPOSIT));

        service.cancelAfterHandover(FIREBASE, BID_ID);

        assertThat(askedPort).containsExactly(BID_ID);
        verify(eventPublisher, atLeastOnce()).publishEvent(any(TripCancelledEvent.class));
    }

    @Test
    void cancelAfterHandover_deliveryBoatBid_unchanged() {
        portAnswer = true;
        stubCancelAfterHandover(bid(BidStatus.HANDED_OVER, PayoutTrigger.DELIVERY));

        service.cancelAfterHandover(FIREBASE, BID_ID);

        assertThat(askedPort).isEmpty();
        verify(eventPublisher, atLeastOnce()).publishEvent(any(TripCancelledEvent.class));
    }

    // ── Annulation du trajet ───────────────────────────────────────────────

    @Test
    void cancelTrip_withDepositedParcel_409AndAlert() {
        portAnswer = true;
        AnnouncementEntity trip = trip();
        when(userRepository.findByFirebaseUid(FIREBASE)).thenReturn(Optional.of(user(TRAVELER_ID)));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(trip));
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(ANN_ID))
                .thenReturn(List.of(bid(BidStatus.IN_TRANSIT, PayoutTrigger.DEPOSIT)));

        assertThatThrownBy(() -> service.cancelTrip(FIREBASE, new CancellationRequest(ANN_ID, "Bateau annulé")))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("deposit-paid-no-refund");
                });

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getValue()).isEqualTo(
                new TripCancelRefusedDepositPaidEvent(ANN_ID, TRAVELER_ID, List.of(BID_ID)));
        // Tous les colis vivants (acceptés compris) verrouillés avant d'être lus.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bidRepository);
        order.verify(bidRepository).lockByAnnouncementIdAndStatusIn(ANN_ID, CancellationService.TRIP_LIVE_STATUSES);
        order.verify(bidRepository).findHandedOverByAnnouncementIdForUpdate(ANN_ID);
        assertThat(CancellationService.TRIP_LIVE_STATUSES).contains(BidStatus.ACCEPTED, BidStatus.HANDED_OVER);
        assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        verify(announcementRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
        verify(cancellationRepository, never()).save(any());
    }

    @Test
    void cancelTrip_boatNoDeposit_refundsAsToday() {
        // Colis déposé d'un colis payé au dépôt mais aucun argent versé (espèces) : procédure de retour comme avant.
        portAnswer = false;
        AnnouncementEntity trip = trip();
        UserEntity traveler = user(TRAVELER_ID);
        when(userRepository.findByFirebaseUid(FIREBASE)).thenReturn(Optional.of(traveler));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(trip));
        BidEntity deposited = bid(BidStatus.HANDED_OVER, PayoutTrigger.DEPOSIT);
        deposited.setPaymentMethod(PaymentMethod.CASH);
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(ANN_ID)).thenReturn(List.of(deposited));
        when(bidRepository.findByAnnouncementIdAndStatusIn(any(), any())).thenReturn(List.of());
        when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());
        when(cancellationRepository.save(any(CancellationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(rematchService.generateForCancellations(any(), any(), any())).thenReturn(Map.of());

        service.cancelTrip(FIREBASE, new CancellationRequest(ANN_ID, "Bateau annulé"));

        assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.CANCELLED);
        assertThat(askedPort).containsExactly(BID_ID);
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
        assertThat(events.getAllValues()).noneMatch(e -> e instanceof TripCancelRefusedDepositPaidEvent);
        assertThat(events.getAllValues()).filteredOn(e -> e instanceof TripCancelledEvent)
                .singleElement()
                .satisfies(e -> assertThat(((TripCancelledEvent) e).getReturnRequiredBidIds()).containsExactly(BID_ID));
    }

    private void stubCancelAfterHandover(BidEntity bid) {
        when(userRepository.findByFirebaseUid(FIREBASE)).thenReturn(Optional.of(user(SENDER_ID)));
        when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(trip()));
        when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());
        when(cancellationRepository.save(any(CancellationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(rematchService.generateForCancellations(any(), any(), any())).thenReturn(Map.of());
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private static BidEntity bid(BidStatus status, PayoutTrigger trigger) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", BID_ID);
        ReflectionTestUtils.setField(b, "senderId", SENDER_ID);
        ReflectionTestUtils.setField(b, "announcementId", ANN_ID);
        ReflectionTestUtils.setField(b, "status", status);
        ReflectionTestUtils.setField(b, "weightKg", new BigDecimal("5"));
        b.setPaymentMethod(PaymentMethod.STRIPE);
        ReflectionTestUtils.setField(b, "payoutTrigger", trigger);
        if (trigger == PayoutTrigger.DEPOSIT) {
            ReflectionTestUtils.setField(b, "payoutTriggerFrozenAt", LocalDateTime.now());
        }
        return b;
    }

    private static AnnouncementEntity trip() {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANN_ID);
        ReflectionTestUtils.setField(a, "travelerId", TRAVELER_ID);
        a.setDepartureAt(OffsetDateTime.now().plusDays(3));
        ReflectionTestUtils.setField(a, "capacityUnit", CapacityUnit.KG_EXACT);
        ReflectionTestUtils.setField(a, "status", AnnouncementStatus.ACTIVE);
        ReflectionTestUtils.setField(a, "availableKg", new BigDecimal("10"));
        return a;
    }
}
