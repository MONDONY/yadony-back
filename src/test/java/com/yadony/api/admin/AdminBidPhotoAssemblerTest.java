package com.yadony.api.admin;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yadony.api.admin.dto.AdminBidPhotosResponse.Photo;
import com.yadony.api.common.StorageService;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidPhotoService;
import com.yadony.api.matching.dto.BidPhotoResponse;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.messaging.MessagingImageEntity;
import com.yadony.api.messaging.MessagingImageRepository;
import com.yadony.api.tracking.TrackingEventEntity;
import com.yadony.api.tracking.TrackingEventRepository;
import com.yadony.api.tracking.TrackingEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminBidPhotoAssemblerTest {

    @Mock BidPhotoService photoService;
    @Mock TrackingEventRepository trackingRepo;
    @Mock ConversationRepository conversationRepo;
    @Mock MessagingImageRepository imageRepo;
    @Mock DisputeRepository disputeRepo;
    @Mock StorageService storageService;

    private static final UUID BID = UUID.randomUUID();
    private static final UUID CONV = UUID.randomUUID();
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 6, 18, 0);

    private AdminBidPhotoAssembler assembler;
    private BidEntity bid;
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        assembler = new AdminBidPhotoAssembler(photoService, trackingRepo, conversationRepo, imageRepo,
                disputeRepo, storageService);
        bid = new BidEntity();
        setField(bid, "id", BID);
        setField(bid, "createdAt", T0);
        setField(bid, "updatedAt", T0.plusDays(3));

        when(photoService.activePhotos(BID)).thenReturn(List.of());
        when(trackingRepo.findByBidIdOrderByScannedAtAsc(BID)).thenReturn(List.of());
        when(conversationRepo.findByBidId(BID)).thenReturn(Optional.empty());
        when(disputeRepo.existsByBidId(BID)).thenReturn(false);
        when(storageService.generatePresignedUrl(any(), eq(Duration.ofMinutes(15))))
                .thenAnswer(i -> "https://signed.test/" + i.getArgument(0));

        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
    }

    private static TrackingEventEntity scan(TrackingEventType type, LocalDateTime at, String key, String gps) {
        TrackingEventEntity e = new TrackingEventEntity();
        setField(e, "id", UUID.randomUUID());
        e.setEventType(type);
        e.setScannedAt(at);
        e.setPhotoUrl(key);
        e.setGpsLabel(gps);
        return e;
    }

    private void withDisputeConversation(MessagingImageEntity... images) {
        ConversationEntity c = new ConversationEntity(BID, UUID.randomUUID(), UUID.randomUUID(), "fs-1");
        setField(c, "id", CONV);
        when(conversationRepo.findByBidId(BID)).thenReturn(Optional.of(c));
        when(disputeRepo.existsByBidId(BID)).thenReturn(true);
        when(imageRepo.findByConversationId(CONV)).thenReturn(List.of(images));
    }

    private static MessagingImageEntity image(String key, boolean purged, LocalDateTime at) {
        MessagingImageEntity i = new MessagingImageEntity(CONV, BID, "m-" + key, UUID.randomUUID(), key, key + "-t");
        setField(i, "id", UUID.randomUUID());
        setField(i, "createdAt", at);
        if (purged) i.markPurged(at);
        return i;
    }

    @Test
    void photos_creation_fromBidPhotos() {
        UUID p = UUID.randomUUID();
        when(photoService.activePhotos(BID)).thenReturn(List.of(new BidPhotoResponse(p, "https://signed.test/bids/1")));

        List<Photo> photos = assembler.photos(bid, AdminBidDetailAssembler.Access.ALL);

        assertThat(photos).singleElement().satisfies(ph -> {
            assertThat(ph.id()).isEqualTo(p.toString());
            assertThat(ph.stage()).isEqualTo("CREATION");
            assertThat(ph.at()).isEqualTo(T0);
            assertThat(ph.url()).isEqualTo("https://signed.test/bids/1");
        });
    }

    @Test
    void photos_scans_skipPurgedAndBlank() {
        TrackingEventEntity purged = scan(TrackingEventType.DEPART, T0, "tracking/a.jpg", null);
        purged.markPhotoPurged(T0.plusDays(1));
        when(trackingRepo.findByBidIdOrderByScannedAtAsc(BID)).thenReturn(List.of(
                purged,
                scan(TrackingEventType.TRANSIT, T0, " ", null),
                scan(TrackingEventType.ARRIVEE, T0, null, null),
                scan(TrackingEventType.DEPART, T0, "tracking/ok.jpg", "Paris")));

        List<Photo> photos = assembler.photos(bid, AdminBidDetailAssembler.Access.ALL);

        assertThat(photos).singleElement().satisfies(ph -> {
            assertThat(ph.stage()).isEqualTo("DEPART");
            assertThat(ph.label()).isEqualTo("DEPART");
            assertThat(ph.gpsLabel()).isEqualTo("Paris");
            assertThat(ph.url()).isEqualTo("https://signed.test/tracking/ok.jpg");
        });
    }

    @Test
    void photos_scans_orderedDepartTransitArrivee() {
        when(trackingRepo.findByBidIdOrderByScannedAtAsc(BID)).thenReturn(List.of(
                scan(TrackingEventType.ARRIVEE, T0.plusHours(1), "k/arrivee.jpg", null),
                scan(TrackingEventType.TRANSIT, T0.plusHours(5), "k/transit2.jpg", null),
                scan(TrackingEventType.DEPART, T0.plusHours(9), "k/depart.jpg", null),
                scan(TrackingEventType.TRANSIT, T0.plusHours(2), "k/transit1.jpg", null)));
        when(photoService.activePhotos(BID)).thenReturn(List.of(new BidPhotoResponse(UUID.randomUUID(), "u")));

        List<Photo> photos = assembler.photos(bid, AdminBidDetailAssembler.Access.ALL);

        assertThat(photos).extracting(Photo::stage)
                .containsExactly("CREATION", "DEPART", "TRANSIT", "TRANSIT", "ARRIVEE");
        assertThat(photos).extracting(Photo::url).containsSubsequence(
                "https://signed.test/k/transit1.jpg", "https://signed.test/k/transit2.jpg");
    }

    @Test
    void photos_refusal_included() {
        bid.setRefusalPhotoUrl("refusals/r.jpg");

        List<Photo> photos = assembler.photos(bid, AdminBidDetailAssembler.Access.ALL);

        assertThat(photos).singleElement().satisfies(ph -> {
            assertThat(ph.stage()).isEqualTo("REFUSAL");
            assertThat(ph.at()).isEqualTo(T0.plusDays(3));
            assertThat(ph.url()).isEqualTo("https://signed.test/refusals/r.jpg");
        });
    }

    @Test
    void photos_refusal_blankIsIgnored() {
        bid.setRefusalPhotoUrl("  ");
        assertThat(assembler.photos(bid, AdminBidDetailAssembler.Access.ALL)).isEmpty();
    }

    @Test
    void photos_dispute_requiresModerationAndDisputeAndExistingDispute() {
        withDisputeConversation(image("messaging/i1.jpg", false, T0));

        // Cas 1 : sans MODERATION_VIEW.
        assertThat(assembler.photos(bid, new AdminBidDetailAssembler.Access(true, true, true, false, true))).isEmpty();
        // Cas 2 : sans DISPUTE_VIEW.
        assertThat(assembler.photos(bid, new AdminBidDetailAssembler.Access(true, true, false, true, true))).isEmpty();
        // Cas 3 : les deux droits, mais aucun litige sur le colis.
        when(disputeRepo.existsByBidId(BID)).thenReturn(false);
        assertThat(assembler.photos(bid, AdminBidDetailAssembler.Access.ALL)).isEmpty();
        verify(imageRepo, never()).findByConversationId(any());

        // Cas positif : tout réuni.
        when(disputeRepo.existsByBidId(BID)).thenReturn(true);
        assertThat(assembler.photos(bid, AdminBidDetailAssembler.Access.ALL)).singleElement().satisfies(ph -> {
            assertThat(ph.stage()).isEqualTo("DISPUTE");
            assertThat(ph.label()).isEqualTo("Image de la conversation");
            assertThat(ph.url()).isEqualTo("https://signed.test/messaging/i1.jpg");
        });
    }

    @Test
    void photos_dispute_skipsPurgedImages() {
        withDisputeConversation(image("messaging/gone.jpg", true, T0), image("messaging/live.jpg", false, T0));

        List<Photo> photos = assembler.photos(bid, AdminBidDetailAssembler.Access.ALL);

        assertThat(photos).extracting(Photo::url).containsExactly("https://signed.test/messaging/live.jpg");
    }

    @Test
    void photos_dispute_deletedConversationIgnored() {
        withDisputeConversation(image("messaging/i.jpg", false, T0));
        ConversationEntity deleted = new ConversationEntity(BID, UUID.randomUUID(), UUID.randomUUID(), "fs-2");
        setField(deleted, "deletedAt", T0);
        when(conversationRepo.findByBidId(BID)).thenReturn(Optional.of(deleted));

        assertThat(assembler.photos(bid, AdminBidDetailAssembler.Access.ALL)).isEmpty();
    }

    @Test
    void photos_unsignableKey_isSkipped() {
        when(storageService.generatePresignedUrl(eq("k/bad.jpg"), any())).thenThrow(new IllegalArgumentException("boom"));
        when(trackingRepo.findByBidIdOrderByScannedAtAsc(BID)).thenReturn(List.of(
                scan(TrackingEventType.DEPART, T0, "k/bad.jpg", null),
                scan(TrackingEventType.TRANSIT, T0, "k/good.jpg", null)));

        List<Photo> photos = assembler.photos(bid, AdminBidDetailAssembler.Access.ALL);

        assertThat(photos).extracting(Photo::stage).containsExactly("TRANSIT");
    }

    @Test
    void photos_storageDown_returnsPartialList() {
        when(photoService.activePhotos(BID)).thenThrow(new IllegalStateException("storage down"));
        when(trackingRepo.findByBidIdOrderByScannedAtAsc(BID)).thenReturn(List.of(
                scan(TrackingEventType.DEPART, T0, "k/depart.jpg", null)));

        List<Photo> photos = assembler.photos(bid, AdminBidDetailAssembler.Access.ALL);

        assertThat(photos).extracting(Photo::stage).containsExactly("DEPART");
    }

    @Test
    void photos_urlsAreNeverStoredOrLogged() {
        String signed = "https://r2.test/k/bad.jpg?X-Amz-Signature=deadbeef";
        when(storageService.generatePresignedUrl(eq("k/bad.jpg"), any()))
                .thenThrow(new IllegalStateException("echec " + signed));
        when(photoService.activePhotos(BID)).thenThrow(new IllegalStateException("echec " + signed));
        when(trackingRepo.findByBidIdOrderByScannedAtAsc(BID)).thenReturn(List.of(
                scan(TrackingEventType.DEPART, T0, "k/bad.jpg", null)));

        assembler.photos(bid, AdminBidDetailAssembler.Access.ALL);

        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).allSatisfy(ev -> {
            String text = ev.getFormattedMessage() + " " + ev.getThrowableProxy();
            assertThat(text).doesNotContain("X-Amz-Signature").doesNotContain("k/bad.jpg").doesNotContain("deadbeef");
        });
    }
}
