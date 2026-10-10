package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminBidDetailResponse;
import com.yadony.api.admin.dto.AdminBidTimelineResponse;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidPhotoService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.dto.BidPhotoResponse;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.tracking.TrackingEventEntity;
import com.yadony.api.tracking.TrackingEventRepository;
import com.yadony.api.tracking.TrackingEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminBidDetailAssemblerTest {

    @Mock BidRepository bidRepo;
    @Mock TrackingEventRepository trackingRepo;
    @Mock UserRepository userRepo;
    @Mock PaymentRepository paymentRepo;
    @Mock DisputeRepository disputeRepo;
    @Mock CancellationRepository cancellationRepo;
    @Mock RatingRepository ratingRepo;
    @Mock ConversationRepository conversationRepo;
    @Mock AuditLogRepository auditRepo;
    @Mock AdminPaymentTimeline paymentTimeline;
    @Mock BidPhotoService photoService;
    @Mock StorageService storageService;
    @Mock FirebaseContactService contactService;

    private static final UUID BID = UUID.randomUUID();
    private static final UUID ANN = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID TRAVELER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 6, 18, 53);

    private AdminBidDetailAssembler assembler;
    private BidEntity bid;
    private AnnouncementEntity ann;

    @BeforeEach
    void setUp() {
        assembler = new AdminBidDetailAssembler(bidRepo, trackingRepo, userRepo, paymentRepo, disputeRepo,
                cancellationRepo, ratingRepo, conversationRepo, auditRepo, paymentTimeline, photoService,
                storageService, contactService);
        bid = new BidEntity();
        setField(bid, "id", BID);
        setField(bid, "createdAt", T0);
        bid.setAnnouncementId(ANN);
        bid.setSenderId(SENDER);
        bid.setStatus(BidStatus.ACCEPTED);
        ann = new AnnouncementEntity();
        setField(ann, "id", ANN);
        ann.setTravelerId(TRAVELER);
        ann.setStatus(AnnouncementStatus.ACTIVE);
        ann.setDepartureCity("Paris");
        ann.setArrivalCity("Bamako");
        ann.setDepartureCountryCode("FR");
        ann.setArrivalCountryCode("ML");
        ann.setDepartureDate(LocalDate.of(2026, 10, 12));
        ann.setArrivalDate(LocalDate.of(2026, 10, 13));
        ann.setTransportMode(TransportMode.PLANE);
        ann.setTotalKg(new BigDecimal("23"));
        ann.setAvailableKg(new BigDecimal("15"));

        when(paymentRepo.findByBidId(BID)).thenReturn(Optional.empty());
        when(disputeRepo.findByBidIdIn(List.of(BID))).thenReturn(List.of());
        when(cancellationRepo.findAllByBidId(BID)).thenReturn(List.of());
        when(ratingRepo.findByBidId(BID)).thenReturn(List.of());
        when(conversationRepo.findByBidId(BID)).thenReturn(Optional.empty());
        when(trackingRepo.findByBidIdOrderByScannedAtAsc(BID)).thenReturn(List.of());
        when(auditRepo.findTop300ByEntityTypeInAndEntityIdInOrderByCreatedAtAscIdAsc(anyCollection(), anyCollection()))
                .thenReturn(List.of());
        when(photoService.activePhotos(BID)).thenReturn(List.of());
        when(userRepo.findAllById(any())).thenReturn(List.of());
    }

    private static UserEntity user(UUID id, String first, String uid) {
        UserEntity u = new UserEntity();
        setField(u, "id", id);
        setField(u, "firstName", first);
        setField(u, "lastName", "Diallo");
        setField(u, "username", first.toLowerCase());
        setField(u, "firebaseUid", uid);
        return u;
    }

    private static AuditLogEntity audit(String type, UUID entityId, String action, UUID actor, LocalDateTime at) {
        AuditLogEntity a = new AuditLogEntity();
        setField(a, "entityType", type);
        setField(a, "entityId", entityId);
        setField(a, "action", action);
        setField(a, "actorId", actor);
        setField(a, "createdAt", at);
        return a;
    }

    // ── Fiche ───────────────────────────────────────────────────────────────

    @Test
    void extras_trajetPersonnesDestinataireEtLiens() {
        UserEntity sender = user(SENDER, "Awa", "uid-s");
        UserEntity traveler = user(TRAVELER, "Moussa", "uid-t");
        setField(traveler, "kycStatus", KycStatus.VERIFIED);
        setField(traveler, "stripeAccountStatus", StripeAccountStatus.ONBOARDING_COMPLETE);
        setField(traveler, "mobileMoneyStatus", MobileMoneyPayoutStatus.NOT_CONFIGURED);
        when(userRepo.findAllById(any())).thenReturn(List.of(sender, traveler));
        when(contactService.getContacts(any())).thenReturn(Map.of(
                "uid-s", new FirebaseContactService.Contact("+33612345678", null),
                "uid-t", FirebaseContactService.Contact.EMPTY));
        when(bidRepo.countByAnnouncementId(ANN)).thenReturn(3L);
        setField(bid, "recipientName", "Fatou");
        setField(bid, "recipientPhone", "+22370001122");
        setField(bid, "confirmationCode", "123456");
        ConversationEntity conv = new ConversationEntity();
        setField(conv, "firestoreConversationId", "fs-42");
        when(conversationRepo.findByBidId(BID)).thenReturn(Optional.of(conv));
        DisputeEntity older = new DisputeEntity();
        setField(older, "id", UUID.randomUUID());
        setField(older, "createdAt", T0);
        DisputeEntity newer = new DisputeEntity();
        UUID disputeId = UUID.randomUUID();
        setField(newer, "id", disputeId);
        setField(newer, "createdAt", T0.plusDays(1));
        setField(newer, "status", "OPEN");
        when(disputeRepo.findByBidIdIn(List.of(BID))).thenReturn(List.of(older, newer));
        when(photoService.activePhotos(BID)).thenReturn(List.of(new BidPhotoResponse(UUID.randomUUID(), "https://r2/p?sig")));

        AdminBidDetailAssembler.Extras x = assembler.extras(bid, ann);

        AdminBidDetailResponse.Trip trip = x.trip();
        assertThat(trip.announcementId()).isEqualTo(ANN);
        assertThat(trip.status()).isEqualTo("ACTIVE");
        assertThat(trip.departureCountryCode()).isEqualTo("FR");
        assertThat(trip.arrivalDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(trip.transportMode()).isEqualTo("PLANE");
        assertThat(trip.otherBidsCount()).isEqualTo(2);
        assertThat(x.sender().name()).isEqualTo("Awa Diallo");
        assertThat(x.sender().username()).isEqualTo("awa");
        assertThat(x.sender().phoneMasked()).isEqualTo("•••• 5678");
        // Les champs de versement ne concernent que le voyageur.
        assertThat(x.sender().stripeConnectUsable()).isNull();
        assertThat(x.traveler().kycStatus()).isEqualTo("VERIFIED");
        assertThat(x.traveler().stripeAccountStatus()).isEqualTo("ONBOARDING_COMPLETE");
        assertThat(x.traveler().stripeConnectUsable()).isTrue();
        assertThat(x.traveler().mobileMoneyUsable()).isFalse();
        assertThat(x.traveler().phoneMasked()).isNull();
        assertThat(x.recipient().name()).isEqualTo("Fatou");
        assertThat(x.recipient().phoneMasked()).isEqualTo("•••• 1122");
        assertThat(x.confirmationCodePresent()).isTrue();
        assertThat(x.links().conversationId()).isEqualTo("fs-42");
        assertThat(x.links().disputeId()).isEqualTo(disputeId);
        assertThat(x.links().disputeStatus()).isEqualTo("OPEN");
        assertThat(x.photoUrls()).containsExactly("https://r2/p?sig");
        assertThat(x.money()).isNull();
    }

    @Test
    void extras_sansAnnonceNiPersonnes() {
        AdminBidDetailAssembler.Extras x = assembler.extras(bid, null);

        assertThat(x.trip()).isNull();
        assertThat(x.sender()).isNull();
        assertThat(x.traveler()).isNull();
        assertThat(x.recipient()).isNull();
        assertThat(x.confirmationCodePresent()).isFalse();
        verify(contactService, never()).getContacts(any());
    }

    @Test
    void extras_paiementDuFilDeNegociation() {
        UUID thread = UUID.randomUUID();
        bid.setLinkedNegotiationThreadId(thread);
        PaymentEntity p = new PaymentEntity();
        UUID paymentId = UUID.randomUUID();
        setField(p, "id", paymentId);
        setField(p, "amount", new BigDecimal("40.00"));
        setField(p, "commissionAmount", new BigDecimal("4.80"));
        setField(p, "status", PaymentStatus.ESCROW);
        setField(p, "rail", PaymentRail.STRIPE);
        setField(p, "currency", "eur");
        Instant captured = Instant.parse("2026-10-06T19:00:00Z");
        setField(p, "capturedAt", captured);
        when(paymentRepo.findLinkedNegotiationPaymentOfBid(BID)).thenReturn(Optional.of(p));

        AdminBidDetailAssembler.Extras x = assembler.extras(bid, ann);

        assertThat(x.money().paymentId()).isEqualTo(paymentId);
        assertThat(x.money().amountCents()).isEqualTo(4000);
        assertThat(x.money().commissionCents()).isEqualTo(480);
        assertThat(x.money().status()).isEqualTo("ESCROW");
        assertThat(x.money().currency()).isEqualTo("EUR");
        assertThat(x.money().capturedAt()).isEqualTo(captured);
        assertThat(x.links().negotiationThreadId()).isEqualTo(thread);
    }

    @Test
    void extras_stockageIndisponible_fichesansPhotos() {
        when(photoService.activePhotos(BID)).thenThrow(new IllegalStateException("s3 down"));

        assertThat(assembler.extras(bid, ann).photoUrls()).isEmpty();
    }

    @Test
    void maskPhone_neGardeQueLesQuatreDerniersChiffres() {
        assertThat(AdminBidDetailAssembler.maskPhone(null)).isNull();
        assertThat(AdminBidDetailAssembler.maskPhone(" ")).isNull();
        assertThat(AdminBidDetailAssembler.maskPhone("123")).isEqualTo("••••");
        assertThat(AdminBidDetailAssembler.maskPhone("+223 70 00 11 22")).isEqualTo("•••• 1122");
    }

    // ── Chronologie ─────────────────────────────────────────────────────────

    /**
     * Le colis de l'exemple (accepté, présence confirmée, aucun scan) : la chronologie était vide
     * parce qu'elle ne lisait que les scans de suivi.
     */
    @Test
    void timeline_colisAccepteSansScan_racontePourtantSaVie() {
        UserEntity traveler = user(TRAVELER, "Moussa", null);
        when(userRepo.findAllById(any())).thenReturn(List.of(traveler));
        when(auditRepo.findTop300ByEntityTypeInAndEntityIdInOrderByCreatedAtAscIdAsc(anyCollection(), anyCollection()))
                .thenReturn(List.of(
                        audit("BID", BID, "CREATED_FROM_THREAD", null, T0),
                        audit("BID", BID, "PRESENCE_CONFIRMED", TRAVELER, T0.plusSeconds(26))));

        List<AdminBidTimelineResponse.Entry> entries = assembler.timeline(bid);

        assertThat(entries).extracting(AdminBidTimelineResponse.Entry::label)
                .containsExactly("CREATED_FROM_THREAD", "PRESENCE_CONFIRMED");
        assertThat(entries.get(1).actorKind()).isEqualTo("USER");
        assertThat(entries.get(1).actorLabel()).isEqualTo("Moussa Diallo");
        assertThat(entries.get(1).source()).isEqualTo("AUDIT");
        assertThat(entries.get(0).actorKind()).isNull();
    }

    @Test
    void timeline_sansAudit_dateDeCreationDuColis() {
        List<AdminBidTimelineResponse.Entry> entries = assembler.timeline(bid);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).label()).isEqualTo("BID_CREATED");
        assertThat(entries.get(0).at()).isEqualTo(T0);
        assertThat(entries.get(0).source()).isEqualTo("BID");
    }

    @Test
    void timeline_scansAvecPhotoPresigneeEtAuditDesScans() {
        TrackingEventEntity depart = new TrackingEventEntity();
        UUID scanId = UUID.randomUUID();
        setField(depart, "id", scanId);
        setField(depart, "eventType", TrackingEventType.DEPART);
        setField(depart, "scannedAt", T0.plusDays(1));
        setField(depart, "photoUrl", "tracking/" + BID + "/1_DEPART.jpg");
        setField(depart, "gpsLabel", "CDG");
        TrackingEventEntity arrivee = new TrackingEventEntity();
        setField(arrivee, "id", UUID.randomUUID());
        setField(arrivee, "eventType", TrackingEventType.ARRIVEE);
        setField(arrivee, "scannedAt", T0.plusDays(2));
        setField(arrivee, "photoUrl", "https://legacy/photo.jpg");
        TrackingEventEntity sansPhoto = new TrackingEventEntity();
        setField(sansPhoto, "scannedAt", T0.plusDays(3));
        when(trackingRepo.findByBidIdOrderByScannedAtAsc(BID)).thenReturn(List.of(depart, arrivee, sansPhoto));
        when(storageService.generatePresignedUrl(eq("tracking/" + BID + "/1_DEPART.jpg"), any()))
                .thenReturn("https://r2/signed");
        when(auditRepo.findTop300ByEntityTypeInAndEntityIdInOrderByCreatedAtAscIdAsc(anyCollection(), anyCollection()))
                .thenReturn(List.of(
                        audit("TRACKING_EVENT", scanId, "SCAN_DEPART", ADMIN, T0.plusDays(1).plusSeconds(1)),
                        // Même identifiant, autre type : ce n'est pas une trace de ce colis.
                        audit("DISPUTE", scanId, "UNRELATED", null, T0.plusDays(1))));
        when(paymentTimeline.adminEmailsOf(any())).thenReturn(Map.of(ADMIN, "ops@yadony.test"));

        List<AdminBidTimelineResponse.Entry> entries = assembler.timeline(bid);

        assertThat(entries).extracting(AdminBidTimelineResponse.Entry::label)
                .containsExactly("BID_CREATED", "DEPART", "SCAN_DEPART", "ARRIVEE", "SCAN");
        AdminBidTimelineResponse.Entry scan = entries.get(1);
        assertThat(scan.kind()).isEqualTo("SCAN");
        assertThat(scan.photoUrl()).isEqualTo("https://r2/signed");
        assertThat(scan.detail()).isEqualTo("CDG");
        assertThat(entries.get(2).actorKind()).isEqualTo("ADMIN");
        assertThat(entries.get(2).actorLabel()).isEqualTo("ops@yadony.test");
        assertThat(entries.get(3).photoUrl()).isEqualTo("https://legacy/photo.jpg");
        assertThat(entries.get(4).photoUrl()).isNull();
    }

    @Test
    void timeline_photoDeScanIllisible_entreeSansPhoto() {
        TrackingEventEntity depart = new TrackingEventEntity();
        setField(depart, "eventType", TrackingEventType.DEPART);
        setField(depart, "scannedAt", T0.plusDays(1));
        setField(depart, "photoUrl", "tracking/x.jpg");
        when(trackingRepo.findByBidIdOrderByScannedAtAsc(BID)).thenReturn(List.of(depart));
        when(storageService.generatePresignedUrl(any(), any())).thenThrow(new IllegalStateException("s3"));

        assertThat(assembler.timeline(bid).get(1).photoUrl()).isNull();
    }

    @Test
    void timeline_paiementLitigeAnnulationNotationEtLivraison() {
        PaymentEntity p = new PaymentEntity();
        setField(p, "id", UUID.randomUUID());
        when(paymentRepo.findByBidId(BID)).thenReturn(Optional.of(p));
        when(paymentTimeline.of(p)).thenReturn(List.of(
                new AdminPaymentTimeline.Entry(T0.plusMinutes(1), "PAYMENT_CREATED", "PAYMENT", null, null, null, Map.of()),
                new AdminPaymentTimeline.Entry(T0.plusMinutes(2), "PAYMENT_CAPTURED_ON_PLATFORM", "AUDIT", ADMIN, "ADMIN",
                        "ops@yadony.test", Map.of())));
        DisputeEntity dispute = new DisputeEntity();
        setField(dispute, "id", UUID.randomUUID());
        setField(dispute, "createdAt", T0.plusDays(4));
        setField(dispute, "type", "DAMAGE");
        setField(dispute, "resolvedAt", OffsetDateTime.of(2026, 10, 12, 10, 0, 0, 0, ZoneOffset.ofHours(2)));
        setField(dispute, "resolutionType", "REFUND_SENDER");
        DisputeEntity auditedDispute = new DisputeEntity();
        UUID auditedId = UUID.randomUUID();
        setField(auditedDispute, "id", auditedId);
        setField(auditedDispute, "createdAt", T0.plusDays(4));
        when(disputeRepo.findByBidIdIn(List.of(BID))).thenReturn(List.of(dispute, auditedDispute));
        CancellationEntity cancellation = new CancellationEntity();
        setField(cancellation, "id", UUID.randomUUID());
        setField(cancellation, "createdAt", T0.plusDays(5));
        when(cancellationRepo.findAllByBidId(BID)).thenReturn(List.of(cancellation));
        RatingEntity rating = new RatingEntity();
        setField(rating, "id", UUID.randomUUID());
        setField(rating, "createdAt", T0.plusDays(7));
        setField(rating, "stars", 5);
        when(ratingRepo.findByBidId(BID)).thenReturn(List.of(rating));
        setField(bid, "deliveredAt", T0.plusDays(3));
        when(auditRepo.findTop300ByEntityTypeInAndEntityIdInOrderByCreatedAtAscIdAsc(anyCollection(), anyCollection()))
                .thenReturn(List.of(
                        audit("BID", BID, "BID_CREATED", null, T0),
                        audit("DISPUTE", auditedId, "DELIVERY_NOSHOW_DISPUTE_OPENED", null, T0.plusDays(4))));

        List<AdminBidTimelineResponse.Entry> entries = assembler.timeline(bid);

        assertThat(entries).extracting(AdminBidTimelineResponse.Entry::label).containsExactly(
                "BID_CREATED", "PAYMENT_CREATED", "PAYMENT_CAPTURED_ON_PLATFORM", "DELIVERED",
                "DELIVERY_NOSHOW_DISPUTE_OPENED", "DISPUTE_OPENED", "CANCELLATION_CREATED",
                "DISPUTE_RESOLVED", "RATING_CREATED");
        assertThat(entries.get(1).kind()).isEqualTo("PAYMENT");
        assertThat(entries.get(1).source()).isEqualTo("PAYMENT");
        assertThat(entries.get(2).source()).isEqualTo("AUDIT");
        assertThat(entries.get(2).actorLabel()).isEqualTo("ops@yadony.test");
        assertThat(entries.get(5).detail()).isEqualTo("DAMAGE");
        assertThat(entries.get(8).detail()).isEqualTo("5/5");
        // 10 h à Paris (UTC+2) = 8 h UTC.
        assertThat(entries.get(7).at()).isEqualTo(LocalDateTime.of(2026, 10, 12, 8, 0));
    }
}
