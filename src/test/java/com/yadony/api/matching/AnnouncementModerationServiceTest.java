package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Lot B : retrait administratif d'une annonce de trajet. */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService — retrait/restauration par la modération")
class AnnouncementModerationServiceTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private AnnouncementService service;

    private static final UUID ANN_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() throws Exception {
        announcement = new AnnouncementEntity();
        setId(announcement, ANN_ID);
        setField(announcement, "travelerId", OWNER_ID);
        setField(announcement, "status", AnnouncementStatus.ACTIVE);
    }

    @Test
    @DisplayName("removeByAdmin : annonce ACTIVE sans bid accepté → REMOVED_BY_ADMIN + audit avec le motif")
    void removeByAdmin_setsStatusAndAudits() {
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANN_ID), anyList())).thenReturn(false);
        when(announcementRepository.save(any())).thenReturn(announcement);

        AnnouncementEntity result = service.removeByAdmin(ANN_ID, ADMIN_ID, "contenu frauduleux");

        assertThat(result.getStatus()).isEqualTo(AnnouncementStatus.REMOVED_BY_ADMIN);

        // Correction mineure (revue) : anyMap() resterait vert même si le motif n'était
        // plus transmis — on capture donc le payload réel pour vérifier son contenu.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("ANNOUNCEMENT"), eq(ANN_ID),
                eq("ANNOUNCEMENT_REMOVED_BY_ADMIN"), eq(ADMIN_ID), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("reason", "contenu frauduleux");
    }

    @Test
    @DisplayName("removeByAdmin : notifie le propriétaire de l'annonce avec le motif")
    void removeByAdmin_notifiesOwner() {
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANN_ID), anyList())).thenReturn(false);
        when(announcementRepository.save(any())).thenReturn(announcement);

        service.removeByAdmin(ANN_ID, ADMIN_ID, "contenu frauduleux");

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationDispatcher).notifyUser(eq(OWNER_ID), anyString(), bodyCaptor.capture(), anyMap());
        assertThat(bodyCaptor.getValue()).contains("contenu frauduleux");
    }

    @Test
    @DisplayName("removeByAdmin : liquide les bids PENDING/PAYMENT_ESCROWED encore ouverts (Critical 1)")
    void removeByAdmin_liquidatesPendingAndEscrowedBids() throws Exception {
        UUID pendingBidId = UUID.randomUUID();
        UUID escrowedSenderId = UUID.randomUUID();
        UUID escrowedBidId = UUID.randomUUID();
        UUID pendingSenderId = UUID.randomUUID();

        BidEntity pendingBid = new BidEntity();
        setId(pendingBid, pendingBidId);
        setField(pendingBid, "senderId", pendingSenderId);
        setField(pendingBid, "status", BidStatus.PENDING);

        BidEntity escrowedBid = new BidEntity();
        setId(escrowedBid, escrowedBidId);
        setField(escrowedBid, "senderId", escrowedSenderId);
        setField(escrowedBid, "status", BidStatus.PAYMENT_ESCROWED);

        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANN_ID), anyList())).thenReturn(false);
        when(bidRepository.findByAnnouncementIdAndStatusIn(
                eq(ANN_ID), eq(List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED))))
                .thenReturn(List.of(pendingBid, escrowedBid));
        when(announcementRepository.save(any())).thenReturn(announcement);

        service.removeByAdmin(ANN_ID, ADMIN_ID, "contenu frauduleux");

        // Le bid escrowé (argent expéditeur déjà bloqué) est bien liquidé — c'est le cas
        // critique : sans ça, l'argent restait bloqué en escrow sans remboursement possible.
        assertThat(escrowedBid.getStatus()).isEqualTo(BidStatus.REJECTED);
        assertThat(escrowedBid.getRejectionReason()).isEqualTo(BidEntity.REJECTION_ANNOUNCEMENT_DELETED);
        assertThat(pendingBid.getStatus()).isEqualTo(BidStatus.REJECTED);
        assertThat(pendingBid.getRejectionReason()).isEqualTo(BidEntity.REJECTION_ANNOUNCEMENT_DELETED);

        verify(bidRepository).save(pendingBid);
        verify(bidRepository).save(escrowedBid);
        verify(auditService).log(eq("BID"), eq(pendingBidId),
                eq("BID_REJECTED_ANNOUNCEMENT_REMOVED_BY_ADMIN"), eq(ADMIN_ID), anyMap());
        verify(auditService).log(eq("BID"), eq(escrowedBidId),
                eq("BID_REJECTED_ANNOUNCEMENT_REMOVED_BY_ADMIN"), eq(ADMIN_ID), anyMap());
    }

    @Test
    @DisplayName("removeByAdmin : refusé si des bids acceptés (ou au-delà) sont en cours")
    void removeByAdmin_rejectedWhenAcceptedBidsExist() {
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANN_ID), anyList())).thenReturn(true);

        assertThatThrownBy(() -> service.removeByAdmin(ANN_ID, ADMIN_ID, "peu importe"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException y = (YadonyBusinessException) e;
                    assertThat(y.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(y.getErrorCode()).isEqualTo("announcement-has-accepted-bids");
                });

        verify(announcementRepository, never()).save(any());
        verifyNoInteractions(notificationDispatcher);
    }

    @Test
    @DisplayName("restoreByAdmin : REMOVED_BY_ADMIN → ACTIVE + audit")
    void restoreByAdmin_returnsToActiveAndAudits() throws Exception {
        setField(announcement, "status", AnnouncementStatus.REMOVED_BY_ADMIN);
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement));
        when(announcementRepository.save(any())).thenReturn(announcement);

        AnnouncementEntity result = service.restoreByAdmin(ANN_ID, ADMIN_ID);

        assertThat(result.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        verify(auditService).log(eq("ANNOUNCEMENT"), eq(ANN_ID),
                eq("ANNOUNCEMENT_RESTORED_BY_ADMIN"), eq(ADMIN_ID), anyMap());
    }

    @Test
    @DisplayName("restoreByAdmin : refusé si l'annonce n'est pas REMOVED_BY_ADMIN")
    void restoreByAdmin_rejectedWhenNotRemoved() {
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement));

        assertThatThrownBy(() -> service.restoreByAdmin(ANN_ID, ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("announcement-not-removed"));

        verify(announcementRepository, never()).save(any());
    }

    @Test
    @DisplayName("removeByAdmin : annonce introuvable → 404")
    void removeByAdmin_announcementNotFound() {
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeByAdmin(ANN_ID, ADMIN_ID, "motif"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException y = (YadonyBusinessException) e;
                    assertThat(y.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(y.getErrorCode()).isEqualTo("announcement-not-found");
                });
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

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
