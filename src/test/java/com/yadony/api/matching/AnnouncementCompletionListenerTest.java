package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.events.ParcelRefusedEvent;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementCompletionListener")
class AnnouncementCompletionListenerTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AuditService auditService;

    private AnnouncementCompletionListener listener;

    private final UUID announcementId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new AnnouncementCompletionListener(
                bidRepository, announcementRepository, auditService);
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private BidEntity completedBid() {
        BidEntity bid = new BidEntity();
        setId(bid, bidId);
        bid.setAnnouncementId(announcementId);
        bid.setStatus(BidStatus.COMPLETED);
        return bid;
    }

    private BidEntity noShowBid() {
        BidEntity bid = new BidEntity();
        setId(bid, bidId);
        bid.setAnnouncementId(announcementId);
        bid.setStatus(BidStatus.NO_SHOW);
        return bid;
    }

    private BidEntity refusedBid() {
        BidEntity bid = new BidEntity();
        setId(bid, bidId);
        bid.setAnnouncementId(announcementId);
        bid.setStatus(BidStatus.PARCEL_REFUSED);
        return bid;
    }

    private AnnouncementEntity announcement(AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, announcementId);
        a.setTravelerId(travelerId);
        a.setStatus(status);
        return a;
    }

    private DeliveryConfirmedEvent deliveryEvent() {
        return new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId);
    }

    private VoyageurNoShowEvent noShowEvent() {
        return new VoyageurNoShowEvent(bidId, travelerId, UUID.randomUUID(), 1);
    }

    private ParcelRefusedEvent refusedEvent() {
        return new ParcelRefusedEvent(bidId, travelerId, UUID.randomUUID(), "contenu non conforme");
    }

    // ── DeliveryConfirmedEvent ───────────────────────────────────────────────

    @Test
    @DisplayName("dernier bid livré + plus aucun ACCEPTED → annonce COMPLETED + audit")
    void lastBidCompleted_setsAnnouncementCompleted() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.ACTIVE);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(announcementId,
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                .thenReturn(false);

        listener.onDeliveryConfirmed(deliveryEvent());

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.COMPLETED);
        verify(announcementRepository).save(ann);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(
                eq("ANNOUNCEMENT"),
                eq(travelerId),
                eq("ANNOUNCEMENT_COMPLETED"),
                eq(announcementId),
                captor.capture());
        assertThat(captor.getValue()).containsEntry("previousStatus", "ACTIVE");
        assertThat(captor.getValue()).containsEntry("lastDeliveredBidId", bidId.toString());
    }

    @Test
    @DisplayName("régression : plus aucun ACCEPTED mais un colis encore HANDED_OVER/IN_TRANSIT → annonce NON complétée")
    void otherInTransitBidRemains_keepsStatus() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.IN_PROGRESS);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        // Aucun ACCEPTED ne reste, mais un colis est encore en vol (HANDED_OVER/IN_TRANSIT).
        // L'ancien code (qui ne testait que ACCEPTED) complétait le trajet à tort → Historique
        // alors qu'une livraison était en cours.
        when(bidRepository.existsByAnnouncementIdAndStatusIn(announcementId,
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                .thenReturn(true);

        listener.onDeliveryConfirmed(deliveryEvent());

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.IN_PROGRESS);
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    /** Régression C2 : après un markArrived groupé, tous les colis du trajet passent
     *  ARRIVED. La première livraison confirmée complétait alors l'annonce alors que
     *  les autres colis attendaient encore leur retrait — ARRIVED doit compter comme
     *  « en vol ». Ce test vérifie que la requête d'existence interroge bien ARRIVED. */
    @Test
    @DisplayName("régression C2 : la liste « en vol » interrogée inclut ARRIVED")
    void inFlightQuery_includesArrived() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.IN_PROGRESS);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(announcementId),
                argThat(statuses -> statuses != null && statuses.contains(BidStatus.ARRIVED))))
                .thenReturn(true);

        listener.onDeliveryConfirmed(deliveryEvent());

        // Un colis encore ARRIVED (non retiré) empêche la complétion du trajet.
        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.IN_PROGRESS);
        verify(announcementRepository, never()).save(any());
        verify(bidRepository).existsByAnnouncementIdAndStatusIn(eq(announcementId),
                argThat(statuses -> statuses.contains(BidStatus.ARRIVED)));
    }

    @Test
    @DisplayName("il reste un bid en vol (ACCEPTED/HANDED_OVER/IN_TRANSIT) → annonce inchangée")
    void otherAcceptedBidsRemain_keepsStatus() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.FULL);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(announcementId,
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                .thenReturn(true);

        listener.onDeliveryConfirmed(deliveryEvent());

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.FULL);
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("annonce déjà COMPLETED → idempotent (pas d'audit)")
    void alreadyCompleted_isIdempotent() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.COMPLETED);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));

        listener.onDeliveryConfirmed(deliveryEvent());

        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
        verify(bidRepository, never()).existsByAnnouncementIdAndStatusIn(any(), any());
    }

    @Test
    @DisplayName("annonce CANCELLED → on ne touche pas")
    void cancelled_doesNothing() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.CANCELLED);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));

        listener.onDeliveryConfirmed(deliveryEvent());

        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Lot B (correction 4) : annonce REMOVED_BY_ADMIN → on ne l'écrase pas en COMPLETED")
    void removedByAdmin_doesNothing() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.REMOVED_BY_ADMIN);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));

        listener.onDeliveryConfirmed(deliveryEvent());

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.REMOVED_BY_ADMIN);
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("bid inconnu → log warn, no-op")
    void unknownBid_skips() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(deliveryEvent());

        verify(announcementRepository, never()).findById(any());
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("annonce inconnue → log warn, no-op")
    void unknownAnnouncement_skips() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(completedBid()));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(deliveryEvent());

        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    // ── VoyageurNoShowEvent ──────────────────────────────────────────────────

    @Test
    @DisplayName("no-show sur dernier bid ACCEPTED → annonce IN_PROGRESS → COMPLETED")
    void noShow_lastBid_completesAnnouncement() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(noShowBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.IN_PROGRESS);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(announcementId,
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                .thenReturn(false);

        listener.onVoyageurNoShow(noShowEvent());

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.COMPLETED);
        verify(announcementRepository).save(ann);
    }

    @Test
    @DisplayName("no-show sur annonce ACTIVE avec bids ACCEPTED restants → annonce inchangée")
    void noShow_remainingAccepted_keepsStatus() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(noShowBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.ACTIVE);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(announcementId,
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                .thenReturn(true);

        listener.onVoyageurNoShow(noShowEvent());

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        verify(announcementRepository, never()).save(any());
    }

    // ── ParcelRefusedEvent ───────────────────────────────────────────────────

    @Test
    @DisplayName("parcel refused sur dernier bid ACCEPTED → annonce COMPLETED")
    void parcelRefused_lastBid_completesAnnouncement() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(refusedBid()));
        AnnouncementEntity ann = announcement(AnnouncementStatus.IN_PROGRESS);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(announcementId,
                List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                .thenReturn(false);

        listener.onParcelRefused(refusedEvent());

        assertThat(ann.getStatus()).isEqualTo(AnnouncementStatus.COMPLETED);
        verify(announcementRepository).save(ann);
    }

    @Test
    @DisplayName("parcel refused bid inconnu → no-op")
    void parcelRefused_unknownBid_skips() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onParcelRefused(refusedEvent());

        verify(announcementRepository, never()).findById(any());
        verify(announcementRepository, never()).save(any());
    }

    @Test
    @DisplayName("no-show bid inconnu → log warn, no-op")
    void noShow_unknownBid_skips() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onVoyageurNoShow(noShowEvent());

        verify(announcementRepository, never()).findById(any());
        verify(announcementRepository, never()).save(any());
    }
}
