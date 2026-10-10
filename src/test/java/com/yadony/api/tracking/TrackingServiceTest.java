package com.yadony.api.tracking;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.i18n.TestMessages;
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
import com.yadony.api.tracking.events.ConfirmationCodeBlockedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.AfterEach;
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
    @Mock com.yadony.api.matching.reception.BidRecipientLinkRepository recipientLinkRepository;

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
                auditService, eventPublisher, storageService, notificationDispatcher,
                TestMessages.resolver(), recipientLinkRepository);
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://yadony.app");
        lenient().when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
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

    /** Trajet parti avant-hier : la livraison peut être confirmée (FLUTTER-CB). */
    private AnnouncementEntity buildDepartedAnnouncement() {
        AnnouncementEntity a = buildAnnouncement();
        a.setDepartureDate(LocalDate.now(ZoneOffset.UTC).minusDays(2));
        a.setDepartureTime(LocalTime.of(6, 0));
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

    @Test
    void getQrCode_confirmedRecipient_returnsQrResponse() {
        // FLUTTER-7Y : le destinataire confirmé montre le QR du colis au voyageur
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qr-token-abc");
        UUID recipientId = UUID.randomUUID();
        UserEntity recipient = buildUser(recipientId, "uid-recipient");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-recipient")).thenReturn(Optional.of(recipient));
        when(recipientLinkRepository.existsByBidIdAndRecipientUserIdAndStatus(bidId, recipientId,
                com.yadony.api.matching.reception.ReceptionLinkStatus.CONFIRMED)).thenReturn(true);

        var resp = service.getQrCode(bidId, "uid-recipient");

        assertThat(resp.bidId()).isEqualTo(bidId);
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

    private void stubDepartScan(BidEntity bid) {
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        lenient().when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });
    }

    private QrScanRequest departWithNumber(String number) {
        return new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, null, null, null, number);
    }

    @Test
    void processScan_departWithTheRightTrackingNumber_isAcceptedWhateverTheCase() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setTrackingNumber("DON-AB23CD45");
        stubDepartScan(bid);

        TrackingEventResponse resp = service.processScan(departWithNumber("  don-ab23cd45 "), "uid-traveler");

        assertThat(resp.eventType()).isEqualTo("DEPART");
    }

    @Test
    void processScan_departWithAWrongTrackingNumber_isRefused_beforeAnyInsert() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setTrackingNumber("DON-AB23CD45");
        stubDepartScan(bid);

        assertYadonyError(() -> service.processScan(departWithNumber("DON-ZZZZZZZZ"), "uid-traveler"),
                "tracking-number-mismatch");
        verify(trackingEventRepository, never()).save(any());
        assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
    }

    @Test
    void processScan_departWithoutNumber_staysAcceptedForInstalledApps() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setTrackingNumber("DON-AB23CD45");
        stubDepartScan(bid);

        assertThat(service.processScan(departWithNumber(null), "uid-traveler").eventType()).isEqualTo("DEPART");
    }

    @Test
    void processScan_departWithoutNumber_isRefusedOnceTheNumberIsRequired() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setTrackingNumber("DON-AB23CD45");
        stubDepartScan(bid);
        ReflectionTestUtils.setField(service, "requireNumberOnDepart", true);

        assertYadonyError(() -> service.processScan(departWithNumber(" "), "uid-traveler"),
                "tracking-number-required");
    }

    @Test
    void processScan_departScannedByQr_needsNoNumberEvenWhenItIsRequired() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setTrackingNumber("DON-AB23CD45");
        stubDepartScan(bid);
        ReflectionTestUtils.setField(service, "requireNumberOnDepart", true);
        QrScanRequest byQr = new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, null, null,
                ScanMethod.QR, null);

        assertThat(service.processScan(byQr, "uid-traveler").eventType()).isEqualTo("DEPART");
    }

    @Test
    void processScan_departByRowButtonWithoutNumber_isRefusedOnceRequired() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setTrackingNumber("DON-AB23CD45");
        stubDepartScan(bid);
        ReflectionTestUtils.setField(service, "requireNumberOnDepart", true);
        QrScanRequest manual = new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, null, null,
                ScanMethod.MANUAL, null);

        assertYadonyError(() -> service.processScan(manual, "uid-traveler"), "tracking-number-required");
    }

    @Test
    void processScan_transitIgnoresTheTrackingNumber() {
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        bid.setTrackingNumber("DON-AB23CD45");
        stubDepartScan(bid);
        ReflectionTestUtils.setField(service, "requireNumberOnDepart", true);

        QrScanRequest transit = new QrScanRequest(bidId, TrackingEventType.TRANSIT, null, null, null, null, null);

        assertThat(service.processScan(transit, "uid-traveler").eventType()).isEqualTo("TRANSIT");
    }

    @Test
    void processScan_departAlreadyScanned_returnsTheExistingEvent_withoutAnyEffect() {
        // FLUTTER-JV / YADONY-BACK-STAGING-8 : le même DEPART renvoyé 0,5 s plus tard (envoi
        // direct + file hors ligne) est idempotent : l'étape existante, aucun insert, ni
        // code, ni audit, ni notification, ni événement.
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        bid.setConfirmationCode("654321");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        TrackingEventEntity existing = new TrackingEventEntity();
        setId(existing, UUID.randomUUID());
        existing.setBidId(bidId);
        existing.setEventType(TrackingEventType.DEPART);
        existing.setScannedAt(LocalDateTime.of(2026, 10, 10, 14, 45));
        when(trackingEventRepository.findFirstByBidIdAndEventTypeOrderByScannedAtAsc(bidId, TrackingEventType.DEPART))
                .thenReturn(Optional.of(existing));
        QrScanRequest req = new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, null, null);

        TrackingService.ScanOutcome outcome = service.recordScan(req, "uid-traveler");

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.event().id()).isEqualTo(existing.getId());
        assertThat(outcome.event().eventType()).isEqualTo("DEPART");
        assertThat(bid.getConfirmationCode()).isEqualTo("654321");
        verify(bidRepository).lockForUpdate(bidId);
        verify(trackingEventRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
        verifyNoInteractions(auditService, notificationDispatcher, eventPublisher);
    }

    @Test
    void processScan_departReplayedAfterDelivery_isStillIdempotent() {
        // Rejeu tardif de la file hors ligne : le colis est livré, le DEPART existe.
        BidEntity bid = buildBid(BidStatus.COMPLETED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        TrackingEventEntity existing = new TrackingEventEntity();
        setId(existing, UUID.randomUUID());
        existing.setBidId(bidId);
        existing.setEventType(TrackingEventType.DEPART);
        existing.setScannedAt(LocalDateTime.of(2026, 10, 10, 14, 45));
        when(trackingEventRepository.findFirstByBidIdAndEventTypeOrderByScannedAtAsc(bidId, TrackingEventType.DEPART))
                .thenReturn(Optional.of(existing));

        assertThat(service.processScan(
                new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, null, null),
                "uid-traveler").id()).isEqualTo(existing.getId());
        verify(trackingEventRepository, never()).save(any());
    }

    @Test
    void processScan_departReplayByAnotherUser_isForbidden() {
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity stranger = buildUser(UUID.randomUUID(), "uid-stranger");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-stranger")).thenReturn(Optional.of(stranger));

        Throwable thrown = catchThrowable(() -> service.recordScan(
                new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, null, null), "uid-stranger"));

        assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("forbidden");
        verify(trackingEventRepository, never()).findFirstByBidIdAndEventTypeOrderByScannedAtAsc(any(), any());
    }

    @Test
    void processScan_newDepart_isCreated_andFlushedBeforeTheEffects() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        stubDepartScan(bid);

        TrackingService.ScanOutcome outcome = service.recordScan(
                new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, null, null), "uid-traveler");

        assertThat(outcome.created()).isTrue();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(trackingEventRepository, auditService);
        order.verify(trackingEventRepository).save(any());
        order.verify(trackingEventRepository).flush();
        order.verify(auditService, org.mockito.Mockito.atLeastOnce())
                .log(any(), any(), any(), any(), any());
    }

    @Test
    void findRecordedDepart_returnsTheWinningEvent() {
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        TrackingEventEntity existing = new TrackingEventEntity();
        setId(existing, UUID.randomUUID());
        existing.setBidId(bidId);
        existing.setEventType(TrackingEventType.DEPART);
        existing.setScannedAt(LocalDateTime.of(2026, 10, 10, 14, 45));
        when(trackingEventRepository.findFirstByBidIdAndEventTypeOrderByScannedAtAsc(bidId, TrackingEventType.DEPART))
                .thenReturn(Optional.of(existing));

        assertThat(service.findRecordedDepart(bidId, "uid-traveler").id()).isEqualTo(existing.getId());
    }

    @Test
    void findRecordedDepart_withoutEvent_is409ScanAlreadyRecorded() {
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(trackingEventRepository.findFirstByBidIdAndEventTypeOrderByScannedAtAsc(bidId, TrackingEventType.DEPART))
                .thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.findRecordedDepart(bidId, "uid-traveler"));

        assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("scan-already-recorded");
        assertThat(((YadonyBusinessException) thrown).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
    }

    @Test
    void findRecordedDepart_guards() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        assertThat(((YadonyBusinessException) catchThrowable(() -> service.findRecordedDepart(bidId, "u")))
                .getErrorCode()).isEqualTo("bid-not-found");

        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.empty());
        assertThat(((YadonyBusinessException) catchThrowable(() -> service.findRecordedDepart(bidId, "u")))
                .getErrorCode()).isEqualTo("announcement-not-found");

        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildAnnouncement()));
        when(userRepository.findByFirebaseUid("u")).thenReturn(Optional.empty());
        assertThat(((YadonyBusinessException) catchThrowable(() -> service.findRecordedDepart(bidId, "u")))
                .getErrorCode()).isEqualTo("user-not-found");

        when(userRepository.findByFirebaseUid("u")).thenReturn(Optional.of(buildUser(UUID.randomUUID(), "u")));
        assertThat(((YadonyBusinessException) catchThrowable(() -> service.findRecordedDepart(bidId, "u")))
                .getErrorCode()).isEqualTo("forbidden");
    }

    @Test
    void processScan_transitEvent_success_noCodeGeneration() {
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
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
        // FLUTTER-AE : le trajet quitte le marché dès qu'un colis est en route.
        verify(eventPublisher).publishEvent(
                new com.yadony.api.tracking.events.ParcelInTransitEvent(bidId, bid.getAnnouncementId()));
    }

    /**
     * Le scan TRANSIT est facultatif, le DEPART ne l'est pas : c'est lui qui génère le
     * code de confirmation du destinataire. Un TRANSIT avant le DEPART laissait la
     * livraison inconfirmable (code-not-generated).
     */
    @Test
    void processScan_transitBeforeDepart_isRejected_andNothingRecorded() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        QrScanRequest req = new QrScanRequest(bidId, TrackingEventType.TRANSIT, null, null, null, null, null);
        assertYadonyError(() -> service.processScan(req, "uid-traveler"), "depart-required");

        assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        verify(trackingEventRepository, never()).save(any());
    }

    // ── confirmDelivery ───────────────────────────────────────────────────────

    @Test
    void confirmDelivery_codeNotGenerated_throwsUnprocessable() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        AnnouncementEntity ann = buildDepartedAnnouncement();
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
        AnnouncementEntity ann = buildDepartedAnnouncement();
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
        AnnouncementEntity ann = buildDepartedAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        ConfirmDeliveryRequest req = new ConfirmDeliveryRequest("000000");
        assertYadonyError(() -> service.confirmDelivery(bidId, req, "uid-traveler"), "code-blocked");
        assertThat(bid.getConfirmationCode()).isNull(); // code reset
        verify(auditService).log(eq("TRACKING_CONFIRMATION_CODE"), eq(bidId), eq("CODE_ATTEMPTS_EXCEEDED"),
                eq(travelerId), any());
        verify(eventPublisher).publishEvent(new ConfirmationCodeBlockedEvent(bidId, senderId));
    }

    // FLUTTER-G1 : le troisième essai faux bloque le code tout de suite, trace l'action
    // et prévient l'expéditeur — avant, le code n'était effacé qu'au quatrième essai,
    // sans que personne ne sache qu'il fallait en générer un nouveau.
    @Test
    void confirmDelivery_thirdWrongAttempt_blocksCodeAuditsAndNotifiesSender() {
        BidEntity bid = buildBid(BidStatus.IN_TRANSIT, "qt");
        bid.setConfirmationCode("654321");
        bid.setConfirmationCodeAttempts(2);
        bid.setConfirmationCodePublicEnabled(true);
        AnnouncementEntity ann = buildDepartedAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        Throwable thrown = catchThrowable(() ->
                service.confirmDelivery(bidId, new ConfirmDeliveryRequest("000000"), "uid-traveler"));

        assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("code-blocked");
        assertThat(thrown.getMessage()).contains("expéditeur").contains("nouveau");
        assertThat(bid.getConfirmationCode()).isNull();
        assertThat(bid.getConfirmationCodeAttempts()).isZero();
        assertThat(bid.isConfirmationCodePublicEnabled()).isFalse();
        assertThat(bid.getStatus()).isEqualTo(BidStatus.IN_TRANSIT);
        verify(bidRepository).save(bid);
        verify(auditService).log(eq("TRACKING_CONFIRMATION_CODE"), eq(bidId), eq("CODE_ATTEMPTS_EXCEEDED"),
                eq(travelerId), argThat(p -> "3".equals(p.get("attempts"))));
        verify(eventPublisher).publishEvent(new ConfirmationCodeBlockedEvent(bidId, senderId));
    }

    @Test
    void confirmDelivery_secondWrongAttempt_keepsCodeAndPublishesNothing() {
        BidEntity bid = buildBid(BidStatus.IN_TRANSIT, "qt");
        bid.setConfirmationCode("654321");
        bid.setConfirmationCodeAttempts(1);
        AnnouncementEntity ann = buildDepartedAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        assertYadonyError(() -> service.confirmDelivery(bidId, new ConfirmDeliveryRequest("000000"), "uid-traveler"),
                "code-incorrect");
        assertThat(bid.getConfirmationCode()).isEqualTo("654321");
        assertThat(bid.getConfirmationCodeAttempts()).isEqualTo(2);
        verify(eventPublisher, never()).publishEvent(any(ConfirmationCodeBlockedEvent.class));
    }

    // FLUTTER-G1 : sans code sur un colis déjà remis, le voyageur était renvoyé au scan
    // DEPART, déjà fait. Il doit savoir que seul l'expéditeur peut débloquer la situation.
    @Test
    void confirmDelivery_codeClearedAfterHandover_saysAskSenderForNewCode() {
        BidEntity bid = buildBid(BidStatus.ARRIVED, "qt");
        AnnouncementEntity ann = buildDepartedAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        Throwable thrown = catchThrowable(() ->
                service.confirmDelivery(bidId, new ConfirmDeliveryRequest("123456"), "uid-traveler"));

        assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("code-blocked");
        assertThat(thrown.getMessage()).contains("expéditeur").doesNotContain("scannez");
        verify(eventPublisher, never()).publishEvent(any(ConfirmationCodeBlockedEvent.class));
    }

    @Test
    void confirmDelivery_correctCode_publishesDeliveryConfirmedEvent() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeAttempts(0);
        AnnouncementEntity ann = buildDepartedAnnouncement();
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

    /** Feedback FLUTTER-2A : l'arrivée n'enregistrait jamais de lieu. */
    @Test
    void confirmDelivery_withGps_recordsArrivalLocation() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeAttempts(0);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildDepartedAnnouncement()));
        when(userRepository.findByFirebaseUid("uid-traveler"))
                .thenReturn(Optional.of(buildUser(travelerId, "uid-traveler")));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });

        ConfirmDeliveryRequest req = new ConfirmDeliveryRequest("123456", null, null,
                new java.math.BigDecimal("14.6928"), new java.math.BigDecimal("-17.4467"), "Dakar, Plateau");
        service.confirmDelivery(bidId, req, "uid-traveler");

        ArgumentCaptor<TrackingEventEntity> saved = ArgumentCaptor.forClass(TrackingEventEntity.class);
        verify(trackingEventRepository).save(saved.capture());
        assertThat(saved.getValue().getGpsLat()).isEqualByComparingTo("14.6928");
        assertThat(saved.getValue().getGpsLon()).isEqualByComparingTo("-17.4467");
        assertThat(saved.getValue().getGpsLabel()).isEqualTo("Dakar, Plateau");
    }

    @Test
    void confirmDelivery_bidArrived_succeeds() {
        BidEntity bid = buildBid(BidStatus.ARRIVED, "qt");
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeExpiry(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        bid.setConfirmationCodeAttempts(0);
        AnnouncementEntity announcement = buildDepartedAnnouncement();
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

    @Test
    void confirmDelivery_withPhotoKey_storesPhotoOnArriveeEvent() {
        BidEntity bid = buildBid(BidStatus.IN_TRANSIT, "qt");
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeAttempts(0);
        AnnouncementEntity ann = buildDepartedAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });

        String photoKey = "tracking/" + bidId + "/1700000000_ARRIVEE.jpg";
        TrackingEventResponse resp = service.confirmDelivery(
                bidId, new ConfirmDeliveryRequest("123456", photoKey), "uid-traveler");

        assertThat(resp.eventType()).isEqualTo("ARRIVEE");
        assertThat(resp.photoUrl()).isEqualTo(photoKey);
        ArgumentCaptor<TrackingEventEntity> captor = ArgumentCaptor.forClass(TrackingEventEntity.class);
        verify(trackingEventRepository).save(captor.capture());
        assertThat(captor.getValue().getPhotoUrl()).isEqualTo(photoKey);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.COMPLETED);
    }

    @Test
    void confirmDelivery_withForeignPhotoKey_throwsUnprocessableAndKeepsCode() {
        BidEntity bid = buildBid(BidStatus.IN_TRANSIT, "qt");
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeAttempts(0);
        AnnouncementEntity ann = buildDepartedAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        ConfirmDeliveryRequest req = new ConfirmDeliveryRequest(
                "123456", "tracking/" + UUID.randomUUID() + "/photo.jpg");

        assertYadonyError(() -> service.confirmDelivery(bidId, req, "uid-traveler"), "invalid-photo-url");
        assertThat(bid.getStatus()).isEqualTo(BidStatus.IN_TRANSIT);
        assertThat(bid.getConfirmationCode()).isEqualTo("123456");
        verify(trackingEventRepository, never()).save(any());
    }

    // ── confirmDelivery avant le départ (FLUTTER-CB) ────────────────────────

    /** Trajet dont le départ (heure murale dans {@code zone}) est {@code fromNow} après maintenant. */
    private AnnouncementEntity buildAnnouncementDepartingIn(java.time.Duration fromNow, String zone) {
        AnnouncementEntity a = buildAnnouncement();
        java.time.ZonedDateTime departure = java.time.ZonedDateTime.now(java.time.ZoneId.of(zone)).plus(fromNow);
        a.setTimezone(zone);
        a.setDepartureDate(departure.toLocalDate());
        a.setDepartureTime(departure.toLocalTime());
        return a;
    }

    private BidEntity stubConfirmDelivery(BidStatus status, AnnouncementEntity ann) {
        BidEntity bid = buildBid(status, "qt");
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeAttempts(0);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler"))
                .thenReturn(Optional.of(buildUser(travelerId, "uid-traveler")));
        return bid;
    }

    private void assertRefusedBeforeDeparture(BidEntity bid, BidStatus statusBefore) {
        assertYadonyError(() -> service.confirmDelivery(bidId, new ConfirmDeliveryRequest("123456"), "uid-traveler"),
                "trip-not-departed");
        assertThat(bid.getStatus()).isEqualTo(statusBefore);
        assertThat(bid.getConfirmationCode()).isEqualTo("123456");
        assertThat(bid.getConfirmationCodeAttempts()).isZero();
        verify(eventPublisher, never()).publishEvent(any());
        verify(trackingEventRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = BidStatus.class,
            names = {"ACCEPTED", "HANDED_OVER", "IN_TRANSIT", "ARRIVED"})
    void confirmDelivery_laVeilleDuDepart_refuseQuelQueSoitLeStatut(BidStatus status) {
        // Recette FLUTTER-CB : trajet du 06/10 06:00, code saisi le 05/10 06:36.
        BidEntity bid = stubConfirmDelivery(status,
                buildAnnouncementDepartingIn(java.time.Duration.ofHours(23).plusMinutes(24), "Africa/Dakar"));

        assertRefusedBeforeDeparture(bid, status);
    }

    // ── Mode recette (FLUTTER-FA) : testeur en staging ──────────────────────

    private com.yadony.api.common.RecetteMode recette(boolean enabled, String... profiles) {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles(profiles);
        return new com.yadony.api.common.RecetteMode(enabled, env, auditService);
    }

    private BidEntity stubConfirmDeliveryForTester(boolean tester) {
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeAttempts(0);
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        traveler.setRecetteTester(tester);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(
                buildAnnouncementDepartingIn(java.time.Duration.ofDays(5), "Europe/Paris")));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        return bid;
    }

    @Test
    void confirmDelivery_recetteTesteurEnStaging_livreAvantLeDepart_etTraceLeContournement() {
        service.setRecetteMode(recette(true, "staging"));
        BidEntity bid = stubConfirmDeliveryForTester(true);
        when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });

        TrackingEventResponse resp = service.confirmDelivery(bidId, new ConfirmDeliveryRequest("123456"), "uid-traveler");

        assertThat(resp.eventType()).isEqualTo("ARRIVEE");
        assertThat(bid.getStatus()).isEqualTo(BidStatus.COMPLETED);
        verify(eventPublisher).publishEvent(any(DeliveryConfirmedEvent.class));
        verify(auditService).log(eq("RECETTE"), eq(bidId), eq("RECETTE_DELIVERY_BEFORE_DEPARTURE"),
                eq(travelerId), any());
        verify(auditService, never()).log(any(), any(), eq("DELIVERY_REFUSED_TRIP_NOT_DEPARTED"), any(), any());
    }

    @Test
    void confirmDelivery_recetteProfilProd_testeurSoumisALaRegleNormale() {
        // Propriété allumée par erreur en production : le profil prod ferme le mode.
        service.setRecetteMode(recette(true, "prod"));
        BidEntity bid = stubConfirmDeliveryForTester(true);

        assertRefusedBeforeDeparture(bid, BidStatus.HANDED_OVER);
        verify(auditService, never()).log(eq("RECETTE"), any(), any(), any(), any());
    }

    @Test
    void confirmDelivery_recetteActive_compteNonTesteur_refuse() {
        service.setRecetteMode(recette(true, "staging"));
        BidEntity bid = stubConfirmDeliveryForTester(false);

        assertRefusedBeforeDeparture(bid, BidStatus.HANDED_OVER);
    }

    @Test
    void confirmDelivery_recetteDesactivee_testeur_refuse() {
        service.setRecetteMode(recette(false, "staging"));
        BidEntity bid = stubConfirmDeliveryForTester(true);

        assertRefusedBeforeDeparture(bid, BidStatus.HANDED_OVER);
    }

    @Test
    void confirmDelivery_avantLeDepart_codeFauxNeConsommePasDEssai() {
        BidEntity bid = stubConfirmDelivery(BidStatus.HANDED_OVER,
                buildAnnouncementDepartingIn(java.time.Duration.ofHours(2), "Europe/Paris"));

        assertYadonyError(() -> service.confirmDelivery(bidId, new ConfirmDeliveryRequest("000000"), "uid-traveler"),
                "trip-not-departed");
        assertThat(bid.getConfirmationCodeAttempts()).isZero();
    }

    @Test
    void confirmDelivery_avantLeDepart_refusJournalise_sansDonneePersonnelle() {
        stubConfirmDelivery(BidStatus.HANDED_OVER,
                buildAnnouncementDepartingIn(java.time.Duration.ofMinutes(5), "Europe/Paris"));

        catchThrowable(() -> service.confirmDelivery(bidId, new ConfirmDeliveryRequest("123456"), "uid-traveler"));

        verify(auditService).log("TRACKING_EVENT", bidId, "DELIVERY_REFUSED_TRIP_NOT_DEPARTED", travelerId,
                java.util.Map.of("bidId", bidId.toString(), "bidStatus", "HANDED_OVER"));
    }

    @Test
    void confirmDelivery_avantLeDepart_erreur422_messageFrancais() {
        stubConfirmDelivery(BidStatus.HANDED_OVER,
                buildAnnouncementDepartingIn(java.time.Duration.ofHours(1), "Europe/Paris"));

        Throwable thrown = catchThrowable(() ->
                service.confirmDelivery(bidId, new ConfirmDeliveryRequest("123456"), "uid-traveler"));

        YadonyBusinessException ex = (YadonyBusinessException) thrown;
        assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getErrorCode()).isEqualTo(TrackingService.TRIP_NOT_DEPARTED);
        assertThat(ex.getMessage()).isEqualTo(
                "La livraison ne peut être confirmée qu'après le départ du trajet. Réessayez après le trajet.");
    }

    @Test
    void confirmDelivery_avantLeDepart_messageAnglais() {
        TestMessages.requestWithAcceptLanguage("en");
        stubConfirmDelivery(BidStatus.HANDED_OVER,
                buildAnnouncementDepartingIn(java.time.Duration.ofHours(1), "Europe/Paris"));

        Throwable thrown = catchThrowable(() ->
                service.confirmDelivery(bidId, new ConfirmDeliveryRequest("123456"), "uid-traveler"));

        assertThat(thrown).hasMessage(
                "Delivery can only be confirmed once the trip has departed. Please try again after the trip.");
    }

    @Test
    void confirmDelivery_uneMinuteApresLeDepart_confirme() {
        BidEntity bid = stubConfirmDelivery(BidStatus.HANDED_OVER,
                buildAnnouncementDepartingIn(java.time.Duration.ofMinutes(-1), "Africa/Dakar"));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.confirmDelivery(bidId, new ConfirmDeliveryRequest("123456"), "uid-traveler");

        assertThat(bid.getStatus()).isEqualTo(BidStatus.COMPLETED);
        verify(eventPublisher).publishEvent(any(DeliveryConfirmedEvent.class));
    }

    @Test
    void confirmDelivery_heureDeDepartLueDansLeFuseauDuTrajet_dejaPartiAKiritimati() {
        // 1 h passée à Kiritimati (UTC+14) : lue à Paris, cette heure murale serait ~12 h
        // dans le futur et la livraison serait refusée à tort.
        BidEntity bid = stubConfirmDelivery(BidStatus.IN_TRANSIT,
                buildAnnouncementDepartingIn(java.time.Duration.ofHours(-1), "Pacific/Kiritimati"));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.confirmDelivery(bidId, new ConfirmDeliveryRequest("123456"), "uid-traveler");

        assertThat(bid.getStatus()).isEqualTo(BidStatus.COMPLETED);
    }

    @Test
    void confirmDelivery_heureDeDepartLueDansLeFuseauDuTrajet_pasEncorePartiALosAngeles() {
        // Dans 1 h à Los Angeles (UTC-7/-8) : lue à Paris, cette heure murale serait déjà
        // passée de plusieurs heures et la livraison serait acceptée à tort.
        BidEntity bid = stubConfirmDelivery(BidStatus.IN_TRANSIT,
                buildAnnouncementDepartingIn(java.time.Duration.ofHours(1), "America/Los_Angeles"));

        assertRefusedBeforeDeparture(bid, BidStatus.IN_TRANSIT);
    }

    @Test
    void confirmDelivery_sansHeureDeDepart_refuseLeJourMemeDuDepart() {
        AnnouncementEntity ann = buildAnnouncement();
        ann.setTimezone("Africa/Dakar");
        ann.setDepartureDate(LocalDate.now(java.time.ZoneId.of("Africa/Dakar")));
        ann.setDepartureTime(null);
        BidEntity bid = stubConfirmDelivery(BidStatus.HANDED_OVER, ann);

        assertRefusedBeforeDeparture(bid, BidStatus.HANDED_OVER);
    }

    @Test
    void confirmDelivery_sansHeureDeDepart_accepteLeLendemainDuDepart() {
        AnnouncementEntity ann = buildAnnouncement();
        ann.setTimezone("Africa/Dakar");
        ann.setDepartureDate(LocalDate.now(java.time.ZoneId.of("Africa/Dakar")).minusDays(1));
        ann.setDepartureTime(null);
        BidEntity bid = stubConfirmDelivery(BidStatus.HANDED_OVER, ann);
        when(trackingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.confirmDelivery(bidId, new ConfirmDeliveryRequest("123456"), "uid-traveler");

        assertThat(bid.getStatus()).isEqualTo(BidStatus.COMPLETED);
    }

    @Test
    void confirmDelivery_avantLeDepart_lesControlesDeProprieteRestentPrioritaires() {
        // Un tiers ne doit pas apprendre la date de départ du trajet : 403 d'abord.
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(buildBid(BidStatus.HANDED_OVER, "qt")));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(
                buildAnnouncementDepartingIn(java.time.Duration.ofHours(5), "Europe/Paris")));
        when(userRepository.findByFirebaseUid("uid-other"))
                .thenReturn(Optional.of(buildUser(UUID.randomUUID(), "uid-other")));

        assertYadonyError(() -> service.confirmDelivery(bidId, new ConfirmDeliveryRequest("123456"), "uid-other"),
                "forbidden");
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), anyMap());
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
    void refreshCode_codeErasedAfterTooManyAttempts_regeneratesInsteadOfDeadEnd() {
        // Après trois codes faux, confirmDelivery efface le code. Le colis est HANDED_OVER :
        // un second scan DEPART est refusé et processScan ne régénère qu'en ACCEPTED. Sans ce
        // chemin, le colis n'était plus jamais confirmable.
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        bid.setConfirmationCode(null);
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));

        ConfirmCodeResponse resp = service.refreshConfirmationCode(bidId, "uid-sender");

        assertThat(resp.confirmationCode()).hasSize(6);
        assertThat(bid.getConfirmationCode()).isEqualTo(resp.confirmationCode());
        assertThat(bid.getConfirmationCodeAttempts()).isZero();
    }

    @Test
    void refreshCode_afterThePlannedArrivalWindow_givesACodeStillValid() {
        // FLUTTER-BA : remise plus de 24 h après l'arrivée prévue. Le code régénéré
        // héritait de l'expiration du trajet, déjà passée : chaque essai du voyageur
        // finissait en code-expired, le colis n'était plus jamais confirmable.
        BidEntity bid = buildBid(BidStatus.ARRIVED, "qt");
        bid.setConfirmationCode(null);
        AnnouncementEntity ann = buildAnnouncement();
        ann.setDepartureDate(LocalDate.now(ZoneOffset.UTC).minusDays(10));
        ann.setArrivalDate(LocalDate.now(ZoneOffset.UTC).minusDays(9));
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));

        ConfirmCodeResponse resp = service.refreshConfirmationCode(bidId, "uid-sender");

        assertThat(resp.expiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusHours(23));
        assertThat(bid.getConfirmationCodeExpiry()).isEqualTo(resp.expiresAt());
    }

    @Test
    void confirmDelivery_refusalsAreKeptWhenTheTransactionEnds() throws NoSuchMethodException {
        // FLUTTER-BA : le compteur d'essais et l'effacement d'un code expiré sont écrits
        // juste avant l'erreur. Annulés avec la transaction, la limite de trois essais ne
        // s'appliquait jamais.
        org.springframework.transaction.annotation.Transactional tx = TrackingService.class
                .getMethod("confirmDelivery", UUID.class, ConfirmDeliveryRequest.class, String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx.noRollbackFor()).contains(YadonyBusinessException.class);
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

    /** FLUTTER-4E : un vol de nuit arrive le lendemain ; le code expirait à l'atterrissage. */
    @Test
    void refreshCode_overnightTrip_expiryFromArrivalDay() {
        BidEntity bid = buildBid(BidStatus.ACCEPTED, "qt");
        bid.setConfirmationCode("333333");
        LocalDate departureDate = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        LocalTime arrivalTime = LocalTime.of(6, 30);
        AnnouncementEntity ann = buildAnnouncementWithArrivalTime(arrivalTime);
        ann.setDepartureDate(departureDate);
        ann.setArrivalDate(departureDate.plusDays(1));
        UserEntity sender = buildUser(senderId, "uid-sender");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));

        ConfirmCodeResponse resp = service.refreshConfirmationCode(bidId, "uid-sender");

        assertThat(resp.expiresAt()).isEqualTo(departureDate.plusDays(1).atTime(arrivalTime).plusDays(1));
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

    // ── searchByTrackingNumber : stepLabel i18n (tâche A6) ────────────────────

    @Test
    void searchByTrackingNumber_englishRequest_paymentEscrowed_returnsEnglishLabel() {
        BidEntity bid = buildBid(BidStatus.PAYMENT_ESCROWED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        stubSenderAsCurrentUser();
        TestMessages.requestWithAcceptLanguage("en");

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.currentStep()).isEqualTo("PAYMENT_ESCROWED");
        assertThat(resp.stepLabel()).isEqualTo("Payment on hold, awaiting traveler confirmation");
    }

    @Test
    void searchByTrackingNumber_englishRequest_departScan_returnsEnglishLabel() {
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
        TestMessages.requestWithAcceptLanguage("en");

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.currentStep()).isEqualTo("DEPARTED");
        assertThat(resp.stepLabel()).isEqualTo("Parcel dropped off with the traveler, on its way");
    }

    @Test
    void searchByTrackingNumber_frenchAssertions_stillUnchanged() {
        // Les assertions françaises existantes (« Paiement gelé ») ne changent pas (D10).
        BidEntity bid = buildBid(BidStatus.PAYMENT_ESCROWED, "qt");
        AnnouncementEntity ann = buildAnnouncement();
        when(bidRepository.findByTrackingNumber("TRK000001")).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        stubSenderAsCurrentUser();

        TrackingSearchResponse resp = service.searchByTrackingNumber("TRK000001", "uid-sender");

        assertThat(resp.stepLabel()).isEqualTo("Paiement gelé — confirmation voyageur en attente");
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
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
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

        verify(notificationDispatcher).notifyUser(eq(senderId), contains("récupéré"), any(), argThat(d -> "CONFIRMATION_CODE_READY".equals(d.get("type"))));
        // Le destinataire qui suit le colis dans l'app est prévenu par événement.
        verify(eventPublisher).publishEvent(new com.yadony.api.tracking.events.ParcelDepartedEvent(bidId));
    }

    // ── getEvents : destinataire rattaché (lot 2) ─────────────────────────────

    @Test
    void getEvents_confirmedRecipientCanRead() {
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        UUID recipientId = UUID.randomUUID();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-recipient")).thenReturn(Optional.of(buildUser(recipientId, "uid-recipient")));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildAnnouncement()));
        when(recipientLinkRepository.existsByBidIdAndRecipientUserIdAndStatus(bidId, recipientId,
                com.yadony.api.matching.reception.ReceptionLinkStatus.CONFIRMED)).thenReturn(true);
        when(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId)).thenReturn(List.of());

        assertThat(service.getEvents(bidId, "uid-recipient")).isEmpty();
    }

    @Test
    void getEvents_pendingRecipientIsForbidden() {
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        UUID recipientId = UUID.randomUUID();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-recipient")).thenReturn(Optional.of(buildUser(recipientId, "uid-recipient")));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildAnnouncement()));
        when(recipientLinkRepository.existsByBidIdAndRecipientUserIdAndStatus(bidId, recipientId,
                com.yadony.api.matching.reception.ReceptionLinkStatus.CONFIRMED)).thenReturn(false);

        assertYadonyError(() -> service.getEvents(bidId, "uid-recipient"), "forbidden");
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

    @Test
    void getTripScanHistory_declinedRecipient_nameHiddenFromTraveler() {
        AnnouncementEntity ann = buildAnnouncement();
        UserEntity traveler = buildUser(travelerId, "uid-traveler");
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        bid.setRecipientName("Awa Ndiaye");
        TrackingEventEntity event = new TrackingEventEntity();
        setId(event, UUID.randomUUID());
        event.setBidId(bidId);
        event.setEventType(TrackingEventType.DEPART);
        event.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        var declined = new com.yadony.api.matching.reception.BidRecipientLinkEntity(bidId, UUID.randomUUID());
        declined.respond(com.yadony.api.matching.reception.ReceptionLinkStatus.DECLINED, java.time.OffsetDateTime.now(ZoneOffset.UTC));

        when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(bidRepository.findByAnnouncementId(annId)).thenReturn(List.of(bid));
        when(recipientLinkRepository.findByBidIdIn(List.of(bidId))).thenReturn(List.of(declined));
        when(trackingEventRepository.findByBidIdInOrderByScannedAtDesc(List.of(bidId)))
                .thenReturn(List.of(event));

        List<TripScanHistoryEntryDto> result = service.getTripScanHistory(annId, "uid-traveler");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).donNumber()).isEqualTo("TRK000001");
        assertThat(result.get(0).recipientName()).isNull();
    }

    // ── Provenance QR / numéro (scanMethod) ───────────────────────────────────

    private void stubTransitScanContext() {
        // TRANSIT exige un départ scanné (transit facultatif, #336).
        BidEntity bid = buildBid(BidStatus.HANDED_OVER, "qt");
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildAnnouncement()));
        when(userRepository.findByFirebaseUid("uid-traveler"))
                .thenReturn(Optional.of(buildUser(travelerId, "uid-traveler")));
        when(bidRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });
    }

    @Test
    void processScan_withQrScanMethod_persistsAndReturnsIt() {
        stubTransitScanContext();

        TrackingEventResponse resp = service.processScan(new QrScanRequest(
                bidId, TrackingEventType.TRANSIT, null, null, null, null, null, ScanMethod.QR), "uid-traveler");

        ArgumentCaptor<TrackingEventEntity> captor = ArgumentCaptor.forClass(TrackingEventEntity.class);
        verify(trackingEventRepository).save(captor.capture());
        assertThat(captor.getValue().getScanMethod()).isEqualTo(ScanMethod.QR);
        assertThat(resp.scanMethod()).isEqualTo("QR");
    }

    @Test
    void processScan_withManualScanMethod_persistsAndReturnsIt() {
        stubTransitScanContext();

        TrackingEventResponse resp = service.processScan(new QrScanRequest(
                bidId, TrackingEventType.TRANSIT, null, null, null, null, null, ScanMethod.MANUAL), "uid-traveler");

        ArgumentCaptor<TrackingEventEntity> captor = ArgumentCaptor.forClass(TrackingEventEntity.class);
        verify(trackingEventRepository).save(captor.capture());
        assertThat(captor.getValue().getScanMethod()).isEqualTo(ScanMethod.MANUAL);
        assertThat(resp.scanMethod()).isEqualTo("MANUAL");
    }

    @Test
    void processScan_withoutScanMethod_storesNullForOldApps() {
        stubTransitScanContext();

        TrackingEventResponse resp = service.processScan(new QrScanRequest(
                bidId, TrackingEventType.TRANSIT, null, null, null, null, null), "uid-traveler");

        ArgumentCaptor<TrackingEventEntity> captor = ArgumentCaptor.forClass(TrackingEventEntity.class);
        verify(trackingEventRepository).save(captor.capture());
        assertThat(captor.getValue().getScanMethod()).isNull();
        assertThat(resp.scanMethod()).isNull();
    }

    @Test
    void confirmDelivery_withScanMethod_storesItOnArriveeEvent() {
        BidEntity bid = buildBid(BidStatus.IN_TRANSIT, "qt");
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeAttempts(0);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildDepartedAnnouncement()));
        when(userRepository.findByFirebaseUid("uid-traveler"))
                .thenReturn(Optional.of(buildUser(travelerId, "uid-traveler")));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });

        TrackingEventResponse resp = service.confirmDelivery(
                bidId, new ConfirmDeliveryRequest("123456", null, ScanMethod.MANUAL), "uid-traveler");

        ArgumentCaptor<TrackingEventEntity> captor = ArgumentCaptor.forClass(TrackingEventEntity.class);
        verify(trackingEventRepository).save(captor.capture());
        assertThat(captor.getValue().getScanMethod()).isEqualTo(ScanMethod.MANUAL);
        assertThat(resp.scanMethod()).isEqualTo("MANUAL");
    }

    @Test
    void confirmDelivery_withoutScanMethod_storesNull() {
        BidEntity bid = buildBid(BidStatus.IN_TRANSIT, "qt");
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeAttempts(0);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildDepartedAnnouncement()));
        when(userRepository.findByFirebaseUid("uid-traveler"))
                .thenReturn(Optional.of(buildUser(travelerId, "uid-traveler")));
        when(trackingEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TrackingEventResponse resp = service.confirmDelivery(
                bidId, new ConfirmDeliveryRequest("123456"), "uid-traveler");

        assertThat(resp.scanMethod()).isNull();
    }

    @Test
    void getEvents_exposesScanMethodOrNull() {
        BidEntity bid = buildBid(BidStatus.IN_TRANSIT, "qt");
        TrackingEventEntity qr = new TrackingEventEntity();
        setId(qr, UUID.randomUUID());
        qr.setBidId(bidId);
        qr.setEventType(TrackingEventType.DEPART);
        qr.setScannedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
        qr.setScanMethod(ScanMethod.QR);
        TrackingEventEntity legacy = new TrackingEventEntity();
        setId(legacy, UUID.randomUUID());
        legacy.setBidId(bidId);
        legacy.setEventType(TrackingEventType.TRANSIT);
        legacy.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(buildUser(senderId, "uid-sender")));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildAnnouncement()));
        when(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bidId)).thenReturn(List.of(qr, legacy));

        List<TrackingEventResponse> events = service.getEvents(bidId, "uid-sender");

        assertThat(events).extracting(TrackingEventResponse::scanMethod).containsExactly("QR", null);
    }

    @Test
    void getTripScanHistory_exposesScanMethodOrNull() {
        BidEntity bid = buildBid(BidStatus.IN_TRANSIT, "qt");
        TrackingEventEntity manual = new TrackingEventEntity();
        setId(manual, UUID.randomUUID());
        manual.setBidId(bidId);
        manual.setEventType(TrackingEventType.TRANSIT);
        manual.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        manual.setScanMethod(ScanMethod.MANUAL);
        TrackingEventEntity legacy = new TrackingEventEntity();
        setId(legacy, UUID.randomUUID());
        legacy.setBidId(bidId);
        legacy.setEventType(TrackingEventType.DEPART);
        legacy.setScannedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildAnnouncement()));
        when(userRepository.findByFirebaseUid("uid-traveler"))
                .thenReturn(Optional.of(buildUser(travelerId, "uid-traveler")));
        when(bidRepository.findByAnnouncementId(annId)).thenReturn(List.of(bid));
        when(trackingEventRepository.findByBidIdInOrderByScannedAtDesc(List.of(bidId)))
                .thenReturn(List.of(manual, legacy));

        List<TripScanHistoryEntryDto> result = service.getTripScanHistory(annId, "uid-traveler");

        assertThat(result).extracting(TripScanHistoryEntryDto::scanMethod).containsExactly("MANUAL", null);
    }
}
