package com.yadony.api.tracking;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.tracking.dto.ConfirmCodeResponse;
import com.yadony.api.tracking.dto.ConfirmDeliveryRequest;
import com.yadony.api.tracking.dto.QrScanRequest;
import com.yadony.api.tracking.dto.TrackingEventResponse;
import com.yadony.api.tracking.dto.TrackingSearchResponse;
import com.yadony.api.tracking.dto.TripScanHistoryEntryDto;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackingServiceTest {

    @Mock BidRepository bidRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock TrackingEventRepository trackingEventRepository;
    @Mock AuditService auditService;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock StorageService storageService;
    @Mock NotificationDispatcher notificationDispatcher;

    TrackingService service;

    private final UUID senderId   = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId      = UUID.randomUUID();
    private final UUID annId      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrackingService(
                bidRepository, paymentRepository, userRepository,
                announcementRepository, trackingEventRepository,
                auditService, eventPublisher, storageService, notificationDispatcher);
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://yadony.app");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void assertYadonyError(ThrowableAssert.ThrowingCallable callable, String expectedErrorCode) {
        Throwable thrown = catchThrowable(callable);
        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
        assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo(expectedErrorCode);
    }

    private UserEntity buildUser(UUID id, String firebaseUid) {
        UserEntity u = new UserEntity();
        setId(u, id);
        u.setFirebaseUid(firebaseUid);
        return u;
    }

    private BidEntity buildBid(BidStatus status, String qrToken) {
        BidEntity b = new BidEntity();
        setId(b, bidId);
        b.setAnnouncementId(annId);
        b.setSenderId(senderId);
        b.setWeightKg(BigDecimal.valueOf(5.0));
        b.setStatus(status);
        b.setQrToken(qrToken);
        b.setTrackingNumber("TRK000001");
        return b;
    }

    /**
     * Stubbe l'expéditeur du colis comme utilisateur courant : searchByTrackingNumber
     * exige désormais que l'appelant soit expéditeur ou voyageur du colis.
     */
    private void stubSenderAsCurrentUser() {
        when(userRepository.findByFirebaseUid("uid-sender"))
                .thenReturn(Optional.of(buildUser(senderId, "uid-sender")));
    }

    private AnnouncementEntity buildAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, annId);
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setPricePerKg(BigDecimal.valueOf(5.0));
        a.setDepartureDate(LocalDate.now(ZoneOffset.UTC).plusDays(2));
        return a;
    }

    private AnnouncementEntity buildAnnouncementWithArrivalTime(LocalTime arrivalTime) {
        AnnouncementEntity a = buildAnnouncement();
        a.setArrivalTime(arrivalTime);
        return a;
    }

    private void setId(Object entity, UUID id) {
        try {
            Class<?> clazz = entity.getClass();
            Field f = null;
            while (clazz != null) {
                try {
                    f = clazz.getDeclaredField("id");
                    break;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            if (f == null) throw new NoSuchFieldException("id not found in hierarchy of " + entity.getClass().getName());
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── getQrCode ─────────────────────────────────────────────────────────────

    @Test
    void getQrCode_bidNotFound_throwsNotFound() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        assertYadonyError(() -> service.getQrCode(bidId, "uid-001"), "bid-not-found");
    }

    @Test
    void getQrCode_userNotFound_throwsUnauthorized() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(buildBid(BidStatus.ACCEPTED, "qr-token")));
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.empty());
        assertYadonyError(() -> service.getQrCode(bidId, "uid-001"), "user-not-found");
    }

    @Test
    void getQrCode_notSender_throwsForbidden() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qr-token");
        UserEntity other = buildUser(UUID.randomUUID(), "uid-other");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-other")).thenReturn(Optional.of(other));
        assertYadonyError(() -> service.getQrCode(bidId, "uid-other"), "forbidden");
    }

    @Test
    void getQrCode_qrTokenNull_throwsUnprocessable() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, null);
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        assertYadonyError(() -> service.getQrCode(bidId, "uid-sender"), "qr-not-ready");
    }

    @Test
    void getQrCode_success_returnsQrResponse() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qr-token-abc");
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));

        var resp = service.getQrCode(bidId, "uid-sender");

        assertThat(resp.bidId()).isEqualTo(bidId);
        assertThat(resp.scanUrl()).contains(bidId.toString());
        assertThat(resp.qrCodeBase64()).isNotBlank();
    }

    // ── searchByTrackingNumber ────────────────────────────────────────────────

    @Test
    void searchByTrackingNumber_notFound_throwsNotFound() {
        when(bidRepository.findByTrackingNumber("TRK999")).thenReturn(Optional.empty());
        assertYadonyError(() -> service.searchByTrackingNumber("TRK999", "uid-sender"), "tracking-not-found");
    }

    @Test
    void searchByTrackingNumber_pendingBid_returnsCorrectStep() {
        BidEntity bid = buildBid(BidStatus.PENDING, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        stubSenderAsCurrentUser();

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.currentStep()).isEqualTo("PENDING");
    }

    @Test
    void searchByTrackingNumber_rejectedBid_returnsRejected() {
        BidEntity bid = buildBid(BidStatus.REJECTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        stubSenderAsCurrentUser();

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.currentStep()).isEqualTo("REJECTED");
    }

    @Test
    void searchByTrackingNumber_acceptedWithEscrow_returnsPaymentSecured() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        PaymentEntity payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.ESCROW);
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        when(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId)).thenReturn(List.of());

        stubSenderAsCurrentUser();

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.currentStep()).isEqualTo("PAYMENT_SECURED");
    }

    @Test
    void searchByTrackingNumber_hasArriveeEvent_returnsDelivered() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        PaymentEntity payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.ESCROW);
        TrackingEventEntity arriveeEvent = new TrackingEventEntity();
        arriveeEvent.setEventType(TrackingEventType.ARRIVEE);
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        when(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId)).thenReturn(List.of(arriveeEvent));

        stubSenderAsCurrentUser();

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.currentStep()).isEqualTo("DELIVERED");
    }

    @Test
    void searchByTrackingNumber_arrivedWithInstructions_returnsArrivalInstructions() {
        BidEntity bid = buildBid(BidStatus.ARRIVED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        ann.setArrivalInstructions("Retrait au comptoir 3, aéroport Blaise Diagne, de 8h à 18h");
        PaymentEntity payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.ESCROW);
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        when(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId)).thenReturn(List.of());

        stubSenderAsCurrentUser();

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.arrivalInstructions())
                .isEqualTo("Retrait au comptoir 3, aéroport Blaise Diagne, de 8h à 18h");
    }

    @Test
    void searchByTrackingNumber_noArrivalInstructions_returnsNull() {
        BidEntity bid = buildBid(BidStatus.PENDING, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        stubSenderAsCurrentUser();

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.arrivalInstructions()).isNull();
    }

    // ── searchByTrackingNumber : contrôle de propriété ────────────────────────

    @Test
    void searchByTrackingNumber_senderOfBid_returnsResponse() {
        BidEntity bid = buildBid(BidStatus.ARRIVED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        ann.setArrivalInstructions("Retrait au comptoir 3, aéroport Blaise Diagne");
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(userRepository.findByFirebaseUid("uid-sender"))
                .thenReturn(Optional.of(buildUser(senderId, "uid-sender")));

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.trackingNumber()).isEqualTo("TRK000001");
        assertThat(resp.arrivalInstructions()).isEqualTo("Retrait au comptoir 3, aéroport Blaise Diagne");
    }

    @Test
    void searchByTrackingNumber_travelerOfAnnouncement_returnsResponse() {
        BidEntity bid = buildBid(BidStatus.ARRIVED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        ann.setArrivalInstructions("Retrait au comptoir 3, aéroport Blaise Diagne");
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(userRepository.findByFirebaseUid("uid-traveler"))
                .thenReturn(Optional.of(buildUser(travelerId, "uid-traveler")));

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-traveler");

        assertThat(resp.trackingNumber()).isEqualTo("TRK000001");
        assertThat(resp.arrivalInstructions()).isEqualTo("Retrait au comptoir 3, aéroport Blaise Diagne");
    }

    @Test
    void searchByTrackingNumber_thirdParty_throwsForbiddenAndLeaksNoInstructions() {
        BidEntity bid = buildBid(BidStatus.ARRIVED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        ann.setArrivalInstructions("Retrait au comptoir 3, aéroport Blaise Diagne");
        UserEntity outsider = buildUser(UUID.randomUUID(), "uid-other");
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-other")).thenReturn(Optional.of(outsider));

        Throwable thrown = catchThrowable(() -> service.searchByTrackingNumber("TRK000001", "uid-other"));

        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
        YadonyBusinessException ex = (YadonyBusinessException) thrown;
        assertThat(ex.getErrorCode()).isEqualTo("tracking/forbidden");
        assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        assertThat(ex.getMessage()).isEqualTo("Ce colis n'est pas le vôtre");
        assertThat(ex.getMessage()).doesNotContain("comptoir 3");
        verify(paymentRepository, never()).findByBidId(any());
    }

    @Test
    void searchByTrackingNumber_userNotFound_throwsNotFound() {
        BidEntity bid = buildBid(BidStatus.ARRIVED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-unknown")).thenReturn(Optional.empty());

        assertYadonyError(() -> service.searchByTrackingNumber("TRK000001", "uid-unknown"), "user-not-found");
    }

    // ── getEvents ─────────────────────────────────────────────────────────────

    @Test
    void getEvents_forbiddenUser_throwsForbidden() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity outsider = buildUser(UUID.randomUUID(), "uid-other");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-other")).thenReturn(Optional.of(outsider));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));

        assertYadonyError(() -> service.getEvents(bidId, "uid-other"), "forbidden");
    }

    @Test
    void getEvents_senderCanAccess_returnsEvents() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity sender = buildUser(senderId, "uid-sender");
        TrackingEventEntity event = new TrackingEventEntity();
        setId(event, UUID.randomUUID());
        event.setBidId(bidId);
        event.setEventType(TrackingEventType.DEPART);
        event.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId)).thenReturn(List.of(event));

        List<TrackingEventResponse> events = service.getEvents(bidId, "uid-sender");

        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("DEPART");
    }

    // ── processScan ───────────────────────────────────────────────────────────

    @Test
    void processScan_arriveEventType_throwsUnprocessable() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        QrScanRequest req = new QrScanRequest(bidId, TrackingEventType.ARRIVEE, null, null, null, null, null);
        assertYadonyError(() -> service.processScan(req, "uid-traveler"), "use-confirm-delivery");
    }

    @Test
    void processScan_futureTimestamp_throwsUnprocessable() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        LocalDateTime future = LocalDateTime.now(ZoneOffset.UTC).plusHours(2);
        QrScanRequest req = new QrScanRequest(bidId, TrackingEventType.TRANSIT, null, null, null, null, future);
        assertYadonyError(() -> service.processScan(req, "uid-traveler"), "invalid-timestamp");
    }

    @Test
    void processScan_departEvent_success_generatesConfirmationCode() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });
        QrScanRequest req = new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, null, null);
        TrackingEventResponse resp = service.processScan(req, "uid-traveler");

        assertThat(resp.eventType()).isEqualTo("DEPART");
        assertThat(bid.getConfirmationCode()).hasSize(6);
        verify(bidRepository).save(bid);
    }

    @Test
    void processScan_transitEvent_success_noCodeGeneration() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });

        when(bidRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        QrScanRequest req = new QrScanRequest(bidId, TrackingEventType.TRANSIT, null, null, null, null, null);
        TrackingEventResponse resp = service.processScan(req, "uid-traveler");

        assertThat(resp.eventType()).isEqualTo("TRANSIT");
        assertThat(bid.getStatus()).isEqualTo(BidStatus.IN_TRANSIT);
        verify(bidRepository).save(bid);
    }

    // ── confirmDelivery ───────────────────────────────────────────────────────

    @Test
    void confirmDelivery_codeNotGenerated_throwsUnprocessable() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        ConfirmDeliveryRequest req = new ConfirmDeliveryRequest("123456");
        assertYadonyError(() -> service.confirmDelivery(bidId, req, "uid-traveler"), "code-not-generated");
    }

    @Test
    void confirmDelivery_wrongCode_incrementsAttempts() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("654321");
        bid.setConfirmationCodeAttempts(0);
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        ConfirmDeliveryRequest req = new ConfirmDeliveryRequest("000000");
        assertYadonyError(() -> service.confirmDelivery(bidId, req, "uid-traveler"), "code-incorrect");
        assertThat(bid.getConfirmationCodeAttempts()).isEqualTo(1);
    }

    @Test
    void confirmDelivery_tooManyAttempts_resetsCode() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("654321");
        bid.setConfirmationCodeAttempts(3); // already at max
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        ConfirmDeliveryRequest req = new ConfirmDeliveryRequest("000000");
        assertYadonyError(() -> service.confirmDelivery(bidId, req, "uid-traveler"), "too-many-attempts");
        assertThat(bid.getConfirmationCode()).isNull(); // code reset
    }

    @Test
    void confirmDelivery_correctCode_publishesDeliveryConfirmedEvent() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeAttempts(0);
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });

        ConfirmDeliveryRequest req = new ConfirmDeliveryRequest("123456");
        TrackingEventResponse resp = service.confirmDelivery(bidId, req, "uid-traveler");

        assertThat(resp.eventType()).isEqualTo("ARRIVEE");
        assertThat(bid.getConfirmationCode()).isNull();
        ArgumentCaptor<DeliveryConfirmedEvent> captor = ArgumentCaptor.forClass(DeliveryConfirmedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getBidId()).isEqualTo(bidId);
    }

    @Test
    void confirmDelivery_bidArrived_succeeds() {
        BidEntity bid = buildBid(BidStatus.ARRIVED, "qt");
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeExpiry(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        bid.setConfirmationCodeAttempts(0);
        AnnouncementEntity announcement = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });

        TrackingEventResponse response = service.confirmDelivery(
                bidId, new ConfirmDeliveryRequest("123456"), "uid-traveler");

        assertThat(bid.getStatus()).isEqualTo(BidStatus.COMPLETED);
        assertThat(response).isNotNull();
    }

    // ── getConfirmationCode ───────────────────────────────────────────────────

    @Test
    void getConfirmationCode_notSender_throwsForbidden() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("999999");
        UserEntity other = buildUser(UUID.randomUUID(), "uid-other");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-other")).thenReturn(Optional.of(other));

        assertYadonyError(() -> service.getConfirmationCode(bidId, "uid-other"), "forbidden");
    }

    @Test
    void getConfirmationCode_senderGetsCode() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("888888");
        bid.setConfirmationCodeExpiry(LocalDateTime.now(ZoneOffset.UTC).plusDays(2));
        bid.setConfirmationCodePublicEnabled(true);
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));

        ConfirmCodeResponse resp = service.getConfirmationCode(bidId, "uid-sender");

        assertThat(resp.confirmationCode()).isEqualTo("888888");
        assertThat(resp.expiresAt()).isNotNull();
        assertThat(resp.publicPageVisible()).isTrue();
    }

    @Test
    void setConfirmationCodePublicVisible_senderCanPublishAndHideCurrentCode() {
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        bid.setConfirmationCode("888888");
        bid.setConfirmationCodeExpiry(LocalDateTime.now(ZoneOffset.UTC).plusDays(2));
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));

        ConfirmCodeResponse published =
                service.setConfirmationCodePublicVisible(bidId, "uid-sender", true);
        ConfirmCodeResponse hidden =
                service.setConfirmationCodePublicVisible(bidId, "uid-sender", false);

        assertThat(published.publicPageVisible()).isTrue();
        assertThat(hidden.publicPageVisible()).isFalse();
        assertThat(bid.isConfirmationCodePublicEnabled()).isFalse();
        verify(bidRepository, times(2)).save(bid);
    }

    @Test
    void setConfirmationCodePublicVisible_notSender_throwsForbidden() {
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        bid.setConfirmationCode("888888");
        UserEntity other = buildUser(UUID.randomUUID(), "uid-other");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-other")).thenReturn(Optional.of(other));

        assertYadonyError(
                () -> service.setConfirmationCodePublicVisible(bidId, "uid-other", true),
                "forbidden");
    }

    @Test
    void setConfirmationCodePublicVisible_withoutCurrentCode_throwsUnprocessable() {
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));

        assertYadonyError(
                () -> service.setConfirmationCodePublicVisible(bidId, "uid-sender", true),
                "code-not-generated");
    }

    // ── refreshConfirmationCode ───────────────────────────────────────────────

    @Test
    void refreshCode_notSender_throwsForbidden() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("111111");
        UserEntity other = buildUser(UUID.randomUUID(), "uid-other");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-other")).thenReturn(Optional.of(other));

        assertYadonyError(() -> service.refreshConfirmationCode(bidId, "uid-other"), "forbidden");
    }

    @Test
    void refreshCode_codeNotYetGenerated_throwsUnprocessable() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        // confirmationCode is null → DEPART not yet scanned
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));

        assertYadonyError(() -> service.refreshConfirmationCode(bidId, "uid-sender"), "code-not-generated");
    }

    @Test
    void refreshCode_bidNotAccepted_throwsUnprocessable() {
        BidEntity bid = buildBid(BidStatus.COMPLETED, "qt");
        bid.setConfirmationCode("123456");
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));

        assertYadonyError(() -> service.refreshConfirmationCode(bidId, "uid-sender"), "bid-not-accepted");
    }

    @Test
    void refreshCode_success_generatesNewCode() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("000000");
        bid.setConfirmationCodeAttempts(2);
        bid.setConfirmationCodePublicEnabled(true);
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));

        ConfirmCodeResponse resp = service.refreshConfirmationCode(bidId, "uid-sender");

        assertThat(resp.confirmationCode()).hasSize(6);
        assertThat(resp.confirmationCode()).isNotEqualTo("000000");
        assertThat(resp.expiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(bid.getConfirmationCodeAttempts()).isZero();
        assertThat(bid.getConfirmationCodeRefreshCount()).isEqualTo(1);
        assertThat(bid.getConfirmationCodeRefreshWindowStart()).isNotNull();
        assertThat(bid.isConfirmationCodePublicEnabled()).isFalse();
        assertThat(resp.publicPageVisible()).isFalse();
        verifyNoInteractions(notificationDispatcher);
    }

    @Test
    void refreshConfirmationCode_bidArrived_succeeds() {
        BidEntity bid = buildBid(BidStatus.ARRIVED, "qt");
        bid.setConfirmationCode("000000");
        bid.setConfirmationCodeAttempts(2);
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));

        ConfirmCodeResponse resp = service.refreshConfirmationCode(bidId, "uid-sender");

        assertThat(resp.confirmationCode()).hasSize(6);
        assertThat(resp.confirmationCode()).isNotEqualTo("000000");
        assertThat(resp.expiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(bid.getConfirmationCodeAttempts()).isZero();
        assertThat(bid.getStatus()).isEqualTo(BidStatus.ARRIVED);
    }

    @Test
    void refreshCode_withinWindow_incrementsCount() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("111111");
        bid.setConfirmationCodeRefreshCount(3);
        bid.setConfirmationCodeRefreshWindowStart(LocalDateTime.now(ZoneOffset.UTC).minusHours(10));
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));

        service.refreshConfirmationCode(bidId, "uid-sender");

        assertThat(bid.getConfirmationCodeRefreshCount()).isEqualTo(4);
    }

    @Test
    void refreshCode_limitReached_throwsTooManyRequests() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("222222");
        bid.setConfirmationCodeRefreshCount(5);
        bid.setConfirmationCodeRefreshWindowStart(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));

        assertYadonyError(() -> service.refreshConfirmationCode(bidId, "uid-sender"), "too-many-refreshes");
    }

    @Test
    void refreshCode_windowExpired_resetsCount() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("333333");
        bid.setConfirmationCodeRefreshCount(5);
        // Fenêtre ouverte il y a 25h → expirée
        bid.setConfirmationCodeRefreshWindowStart(LocalDateTime.now(ZoneOffset.UTC).minusHours(25));
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));

        service.refreshConfirmationCode(bidId, "uid-sender");

        assertThat(bid.getConfirmationCodeRefreshCount()).isEqualTo(1);
        assertThat(bid.getConfirmationCodeRefreshWindowStart())
                .isAfter(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5));
    }

    @Test
    void refreshCode_withArrivalTime_expiryIsArrivalTimePlusOneDay() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("111111");
        LocalDate departureDate = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        LocalTime arrivalTime = LocalTime.of(14, 30);
        AnnouncementEntity ann = buildAnnouncementWithArrivalTime(arrivalTime);
        ann.setDepartureDate(departureDate);
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));

        ConfirmCodeResponse resp = service.refreshConfirmationCode(bidId, "uid-sender");

        LocalDateTime expected = departureDate.atTime(arrivalTime).plusDays(1);
        assertThat(resp.expiresAt()).isEqualTo(expected);
    }

    @Test
    void refreshCode_withoutArrivalTime_expiryIsDepartureDatePlusThreeDays() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("222222");
        LocalDate departureDate = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        AnnouncementEntity ann = buildAnnouncement();
        ann.setDepartureDate(departureDate);
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));

        ConfirmCodeResponse resp = service.refreshConfirmationCode(bidId, "uid-sender");

        LocalDateTime expected = departureDate.atStartOfDay().plusDays(3);
        assertThat(resp.expiresAt()).isEqualTo(expected);
    }

    // ── searchByTrackingNumber additional branches ────────────────────────────

    @Test
    void searchByTrackingNumber_cancelledBid_returnsCancelled() {
        BidEntity bid = buildBid(BidStatus.CANCELLED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        stubSenderAsCurrentUser();

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.currentStep()).isEqualTo("CANCELLED");
    }

    @Test
    void searchByTrackingNumber_acceptedNoPayment_returnsAccepted() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        stubSenderAsCurrentUser();

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.currentStep()).isEqualTo("ACCEPTED");
    }

    @Test
    void searchByTrackingNumber_voyageurConfirmedEscrow_returnsInTransit() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setVoyageurConfirmed(true);
        AnnouncementEntity ann = buildAnnouncement();
        PaymentEntity payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.ESCROW);
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        when(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId)).thenReturn(List.of());

        stubSenderAsCurrentUser();

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.currentStep()).isEqualTo("IN_TRANSIT");
    }

    @Test
    void searchByTrackingNumber_hasTransitEvent_returnsInTransit() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        PaymentEntity payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.ESCROW);
        TrackingEventEntity transitEvent = new TrackingEventEntity();
        transitEvent.setEventType(TrackingEventType.TRANSIT);
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        when(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId)).thenReturn(List.of(transitEvent));

        stubSenderAsCurrentUser();

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.currentStep()).isEqualTo("IN_TRANSIT");
    }

    @Test
    void searchByTrackingNumber_hasDepartEvent_returnsDeparted() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        PaymentEntity payment = new PaymentEntity();
        payment.setStatus(PaymentStatus.ESCROW);
        TrackingEventEntity departEvent = new TrackingEventEntity();
        departEvent.setEventType(TrackingEventType.DEPART);
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        when(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId)).thenReturn(List.of(departEvent));

        stubSenderAsCurrentUser();

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.currentStep()).isEqualTo("DEPARTED");
    }

    // ── processScan additional branches ──────────────────────────────────────

    @Test
    void processScan_scannerNotTraveler_throwsForbidden() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity outsider = buildUser(UUID.randomUUID(), "uid-other");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-other")).thenReturn(Optional.of(outsider));

        QrScanRequest req = new QrScanRequest(bidId, TrackingEventType.TRANSIT, null, null, null, null, null);
        assertYadonyError(() -> service.processScan(req, "uid-other"), "forbidden");
    }

    @Test
    void processScan_bidNotAccepted_throwsUnprocessable() {
        BidEntity bid = buildBid(BidStatus.PENDING, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        QrScanRequest req = new QrScanRequest(bidId, TrackingEventType.TRANSIT, null, null, null, null, null);
        assertYadonyError(() -> service.processScan(req, "uid-traveler"), "bid-not-accepted");
    }

    @Test
    void processScan_withPastOfflineTimestamp_setsOfflineFields() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });

        LocalDateTime past = LocalDateTime.now(ZoneOffset.UTC).minusHours(2);
        QrScanRequest req = new QrScanRequest(bidId, TrackingEventType.TRANSIT, null, null, null, null, past);
        TrackingEventResponse resp = service.processScan(req, "uid-traveler");

        assertThat(resp.eventType()).isEqualTo("TRANSIT");
        ArgumentCaptor<TrackingEventEntity> captor = ArgumentCaptor.forClass(TrackingEventEntity.class);
        verify(trackingEventRepository).save(captor.capture());
        assertThat(captor.getValue().getOfflineTimestamp()).isNotNull();
        assertThat(captor.getValue().getSyncedAt()).isNotNull();
    }

    @Test
    void processScan_departWithExistingCode_doesNotRegenerate() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("123456"); // already generated
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });

        QrScanRequest req = new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, null, null);
        service.processScan(req, "uid-traveler");

        assertThat(bid.getConfirmationCode()).isEqualTo("123456"); // unchanged
        assertThat(bid.getStatus()).isEqualTo(BidStatus.HANDED_OVER); // status still transitions
        verify(bidRepository).save(bid); // saved for the status transition
    }

    @Test
    void processScan_departWithSenderFcm_sendsFcmNotification() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        UserEntity sender = buildUser(senderId, "uid-sender");
        sender.setFcmToken("fcm-sender-token");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });
        QrScanRequest req = new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, null, null);
        service.processScan(req, "uid-traveler");

        verify(notificationDispatcher).notifyUser(eq(senderId), contains("livraison"), any(), argThat(d -> "CONFIRMATION_CODE_READY".equals(d.get("type"))));
    }

    // ── getEvents additional branches ─────────────────────────────────────────

    @Test
    void getEvents_travelerCanAccess() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        TrackingEventEntity event = new TrackingEventEntity();
        setId(event, UUID.randomUUID());
        event.setBidId(bidId);
        event.setEventType(TrackingEventType.TRANSIT);
        event.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId)).thenReturn(List.of(event));

        List<TrackingEventResponse> events = service.getEvents(bidId, "uid-traveler");

        assertThat(events).hasSize(1);
    }

    @Test
    void getEvents_withHttpPhotoUrl_returnsUrlAsIs() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity sender = buildUser(senderId, "uid-sender");
        TrackingEventEntity event = new TrackingEventEntity();
        setId(event, UUID.randomUUID());
        event.setBidId(bidId);
        event.setEventType(TrackingEventType.DEPART);
        event.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        event.setPhotoUrl("https://storage.example.com/photo.jpg");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId)).thenReturn(List.of(event));

        List<TrackingEventResponse> events = service.getEvents(bidId, "uid-sender");

        assertThat(events.get(0).photoUrl()).isEqualTo("https://storage.example.com/photo.jpg");
    }

    // ── getTripScanHistory ────────────────────────────────────────────────────

    @Test
    void getTripScanHistory_announcementNotFound_throwsNotFound() {
        when(announcementRepository.findById(annId)).thenReturn(Optional.empty());
        assertYadonyError(() -> service.getTripScanHistory(annId, "uid-traveler"), "announcement-not-found");
    }

    @Test
    void getTripScanHistory_notTraveler_throwsForbidden() {
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity outsider = buildUser(UUID.randomUUID(), "uid-other");
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-other")).thenReturn(Optional.of(outsider));

        assertYadonyError(() -> service.getTripScanHistory(annId, "uid-other"), "forbidden");
    }

    @Test
    void getTripScanHistory_noBids_returnsEmptyList() {
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(bidRepository.findByAnnouncementId(annId)).thenReturn(List.of());

        List<TripScanHistoryEntryDto> result = service.getTripScanHistory(annId, "uid-traveler");

        assertThat(result).isEmpty();
    }

    @Test
    void getTripScanHistory_success_returnsSortedEvents() {
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        bid.setRecipientName("Awa Ndiaye");
        TrackingEventEntity event = new TrackingEventEntity();
        setId(event, UUID.randomUUID());
        event.setBidId(bidId);
        event.setEventType(TrackingEventType.DEPART);
        event.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));

        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(bidRepository.findByAnnouncementId(annId)).thenReturn(List.of(bid));
        when(trackingEventRepository.findByBidIdInOrderByScannedAtDesc(List.of(bidId)))
                .thenReturn(List.of(event));

        List<TripScanHistoryEntryDto> result = service.getTripScanHistory(annId, "uid-traveler");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).donNumber()).isEqualTo("TRK000001");
        assertThat(result.get(0).recipientName()).isEqualTo("Awa Ndiaye");
        assertThat(result.get(0).eventType()).isEqualTo("DEPART");
    }
}
