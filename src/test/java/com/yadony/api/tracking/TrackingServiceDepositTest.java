package com.yadony.api.tracking;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PayoutTrigger;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.tracking.dto.QrScanRequest;
import com.yadony.api.tracking.dto.TrackingSearchResponse;
import com.yadony.api.tracking.events.ParcelDepositedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dépôt d'un colis payé au dépôt (BB3) : {@link ParcelDepositedEvent} publié une seule fois, au scan DEPART qui fait
 * passer le colis ACCEPTED → HANDED_OVER, seulement pour un colis {@code DEPOSIT}, et jamais sans photo.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TrackingService : dépôt d'un colis payé au dépôt")
class TrackingServiceDepositTest {

    @Mock BidRepository bidRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock TrackingEventRepository trackingEventRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock StorageService storageService;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock com.yadony.api.matching.reception.BidRecipientLinkRepository recipientLinkRepository;

    private TrackingService service;

    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID annId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrackingService(bidRepository, paymentRepository, userRepository, announcementRepository,
                trackingEventRepository, auditService, eventPublisher, storageService, notificationDispatcher,
                TestMessages.resolver(), recipientLinkRepository);
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://yadony.app");
        lenient().when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    // ── Gabarits ─────────────────────────────────────────────────────────────

    private BidEntity bid(BidStatus status, PayoutTrigger trigger) {
        BidEntity b = new BidEntity();
        setId(b, bidId);
        b.setAnnouncementId(annId);
        b.setSenderId(senderId);
        b.setWeightKg(BigDecimal.valueOf(5));
        b.setStatus(status);
        b.setQrToken("qt");
        b.setTrackingNumber("DON-AB23CD45");
        ReflectionTestUtils.setField(b, "payoutTrigger", trigger);
        return b;
    }

    private AnnouncementEntity boatTrip() {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, annId);
        a.setTravelerId(travelerId);
        a.setDepartureCity("Marseille");
        a.setArrivalCity("Dakar");
        a.setTransportMode(TransportMode.BOAT);
        a.setDepartureDate(LocalDate.now(ZoneOffset.UTC).plusDays(5));
        return a;
    }

    private void stubScan(BidEntity bid) {
        UserEntity traveler = new UserEntity();
        setId(traveler, travelerId);
        traveler.setFirebaseUid("uid-traveler");
        lenient().when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        lenient().when(announcementRepository.findById(annId)).thenReturn(Optional.of(boatTrip()));
        lenient().when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        lenient().when(trackingEventRepository.save(any())).thenAnswer(inv -> {
            TrackingEventEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });
    }

    private String photo() {
        return "tracking/" + bidId + "/1731400000_DEPART.jpg";
    }

    private QrScanRequest depart(String photo) {
        return new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, photo, null, ScanMethod.QR, null);
    }

    private void verifyNoDeposit() {
        verify(eventPublisher, never()).publishEvent(any(ParcelDepositedEvent.class));
        verify(auditService, never()).log(eq("BID"), any(), eq("PARCEL_DEPOSITED"), any(), anyMap());
    }

    private static String errorCode(Throwable thrown) {
        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
        return ((YadonyBusinessException) thrown).getErrorCode();
    }

    // ── Publication ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("DEPART d'un colis DEPOSIT : un événement de dépôt (colis, expéditeur, transporteur) et un audit")
    void departOnDepositBid_publishesDepositedOnce() {
        BidEntity bid = bid(BidStatus.ACCEPTED, PayoutTrigger.DEPOSIT);
        stubScan(bid);

        TrackingService.ScanOutcome outcome = service.recordScan(depart(photo()), "uid-traveler");

        assertThat(outcome.created()).isTrue();
        assertThat(bid.getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        ArgumentCaptor<ParcelDepositedEvent> event = ArgumentCaptor.forClass(ParcelDepositedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new ParcelDepositedEvent(bidId, senderId, travelerId));
        verify(auditService).log(eq("BID"), eq(bidId), eq("PARCEL_DEPOSITED"), eq(travelerId), anyMap());
    }

    @Test
    @DisplayName("DEPART rejoué (envoi direct puis file hors ligne) : un seul événement de dépôt")
    void replayedDepart_publishesDepositOnce() {
        BidEntity bid = bid(BidStatus.ACCEPTED, PayoutTrigger.DEPOSIT);
        stubScan(bid);
        TrackingEventEntity recorded = new TrackingEventEntity();
        setId(recorded, UUID.randomUUID());
        recorded.setBidId(bidId);
        recorded.setEventType(TrackingEventType.DEPART);
        recorded.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(trackingEventRepository.findFirstByBidIdAndEventTypeOrderByScannedAtAsc(bidId, TrackingEventType.DEPART))
                .thenReturn(Optional.empty(), Optional.of(recorded));

        assertThat(service.recordScan(depart(photo()), "uid-traveler").created()).isTrue();
        // Le rejeu arrive sans photo : il renvoie l'étape existante, sans 422 ni second versement.
        assertThat(service.recordScan(depart(null), "uid-traveler").created()).isFalse();

        verify(eventPublisher, times(1)).publishEvent(any(ParcelDepositedEvent.class));
        verify(trackingEventRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("DEPART hors ligne synchronisé plus tard : le dépôt est publié à la synchronisation")
    void offlineDepartSyncedLater_publishesAtSync() {
        BidEntity bid = bid(BidStatus.ACCEPTED, PayoutTrigger.DEPOSIT);
        stubScan(bid);
        QrScanRequest offline = new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, photo(),
                LocalDateTime.now(ZoneOffset.UTC).minusHours(3), ScanMethod.QR, null);

        service.recordScan(offline, "uid-traveler");

        verify(eventPublisher, times(1)).publishEvent(any(ParcelDepositedEvent.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("BID"), eq(bidId), eq("PARCEL_DEPOSITED"), eq(travelerId), audit.capture());
        assertThat(audit.getValue()).containsEntry("offline", "true");
    }

    @Test
    @DisplayName("code de retrait déjà présent : le dépôt est quand même publié (hors du bloc du code)")
    void departWithExistingConfirmationCode_stillPublishesDeposit() {
        BidEntity bid = bid(BidStatus.ACCEPTED, PayoutTrigger.DEPOSIT);
        bid.setConfirmationCode("123456");
        stubScan(bid);

        service.recordScan(depart(photo()), "uid-traveler");

        verify(eventPublisher, times(1)).publishEvent(any(ParcelDepositedEvent.class));
        assertThat(bid.getConfirmationCode()).isEqualTo("123456");
    }

    @Test
    @DisplayName("DEPART d'un colis payé à la livraison : aucun événement de dépôt, photo facultative")
    void departOnDeliveryBid_publishesNothing() {
        BidEntity bid = bid(BidStatus.ACCEPTED, PayoutTrigger.DELIVERY);
        stubScan(bid);

        assertThat(service.recordScan(depart(null), "uid-traveler").created()).isTrue();

        assertThat(bid.getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        verifyNoDeposit();
    }

    @Test
    @DisplayName("TRANSIT d'un colis DEPOSIT déjà déposé : aucun nouvel événement de dépôt")
    void transitScan_publishesNothing() {
        BidEntity bid = bid(BidStatus.HANDED_OVER, PayoutTrigger.DEPOSIT);
        stubScan(bid);

        service.recordScan(new QrScanRequest(bidId, TrackingEventType.TRANSIT, null, null, null, null, null),
                "uid-traveler");

        assertThat(bid.getStatus()).isEqualTo(BidStatus.IN_TRANSIT);
        verifyNoDeposit();
    }

    // ── Refus ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dépôt sans photo : 422 boat-deposit-photo-required, rien d'écrit, aucun versement")
    void depositWithoutPhoto_422_noEvent() {
        BidEntity bid = bid(BidStatus.ACCEPTED, PayoutTrigger.DEPOSIT);
        stubScan(bid);

        for (String missing : new String[] {null, "", "   "}) {
            Throwable thrown = catchThrowable(() -> service.recordScan(depart(missing), "uid-traveler"));
            assertThat(errorCode(thrown)).isEqualTo("boat-deposit-photo-required");
            assertThat(((YadonyBusinessException) thrown).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        verify(trackingEventRepository, never()).save(any());
        assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        verifyNoDeposit();
    }

    @Test
    @DisplayName("photo d'un autre colis : 422 invalid-photo-url avant tout dépôt")
    void depositWithForeignPhoto_422_noEvent() {
        BidEntity bid = bid(BidStatus.ACCEPTED, PayoutTrigger.DEPOSIT);
        stubScan(bid);

        Throwable thrown = catchThrowable(() -> service.recordScan(
                depart("tracking/" + UUID.randomUUID() + "/x.jpg"), "uid-traveler"));

        assertThat(errorCode(thrown)).isEqualTo("invalid-photo-url");
        verify(trackingEventRepository, never()).save(any());
        verifyNoDeposit();
    }

    @Test
    @DisplayName("remise retenue (modification du colis en attente) : 409, aucun événement de dépôt")
    void handoverBlocked_amendmentOpen_noEvent() {
        BidEntity bid = bid(BidStatus.ACCEPTED, PayoutTrigger.DEPOSIT);
        stubScan(bid);
        HandoverBlocker amendmentPending = new HandoverBlocker() {
            @Override
            public Optional<String> blockingCode(UUID id) {
                return Optional.of("handover-amendment-pending");
            }

            @Override
            public void onHandover(HandoverScan scan) {
            }
        };
        service.setHandoverBlockers(List.of(amendmentPending));

        Throwable thrown = catchThrowable(() -> service.recordScan(depart(photo()), "uid-traveler"));

        assertThat(errorCode(thrown)).isEqualTo("handover-amendment-pending");
        assertThat(((YadonyBusinessException) thrown).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verify(trackingEventRepository, never()).save(any());
        verifyNoDeposit();
    }

    // ── Lecture par numéro de suivi ──────────────────────────────────────────

    @Test
    @DisplayName("recherche par numéro : règle de versement servie (DEPOSIT), DELIVERY par défaut")
    void searchByTrackingNumber_servesPayoutTrigger() {
        BidEntity deposit = bid(BidStatus.ACCEPTED, PayoutTrigger.DEPOSIT);
        UserEntity traveler = new UserEntity();
        setId(traveler, travelerId);
        when(bidRepository.findByTrackingNumber("DON-AB23CD45")).thenReturn(Optional.of(deposit));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(boatTrip()));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        TrackingSearchResponse response = service.searchByTrackingNumber("don-ab23cd45", "uid-traveler");

        assertThat(response.payoutTrigger()).isEqualTo("DEPOSIT");
        assertThat(response.transportMode()).isEqualTo("BOAT");

        ReflectionTestUtils.setField(deposit, "payoutTrigger", null);
        assertThat(service.searchByTrackingNumber("DON-AB23CD45", "uid-traveler").payoutTrigger())
                .isEqualTo("DELIVERY");
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> clazz = entity.getClass();
            Field f = null;
            while (clazz != null && f == null) {
                try {
                    f = clazz.getDeclaredField("id");
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            if (f == null) {
                throw new IllegalStateException("id introuvable sur " + entity.getClass());
            }
            f.setAccessible(true);
            f.set(entity, id);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
