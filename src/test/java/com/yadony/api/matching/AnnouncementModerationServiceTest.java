package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
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
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private AnnouncementService service;

    private static final UUID ANN_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    /** Statuts liquidés par removeByAdmin (round 3 : + AWAITING_PAYMENT/NEGOTIATING). */
    private static final List<BidStatus> LIQUIDATABLE_STATUSES = List.of(
            BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED,
            BidStatus.AWAITING_PAYMENT, BidStatus.NEGOTIATING);

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
        when(announcementRepository.findByIdForUpdate(ANN_ID)).thenReturn(Optional.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANN_ID), anyList())).thenReturn(false);
        when(announcementRepository.save(any())).thenReturn(announcement);

        AnnouncementEntity result = service.removeByAdmin(ANN_ID, ADMIN_ID,
                AnnouncementRemovalReason.SUSPECTED_FRAUD, "signalé par Awa Ndiaye, ticket #4821");

        assertThat(result.getStatus()).isEqualTo(AnnouncementStatus.REMOVED_BY_ADMIN);

        // anyMap() resterait vert même si le motif n'était plus transmis — on capture donc le
        // payload réel. L'audit reçoit les DEUX : le motif catalogué et la note interne
        // complète, celle-ci n'ayant plus à être auto-censurée pour protéger le signalant.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("ANNOUNCEMENT"), eq(ANN_ID),
                eq("ANNOUNCEMENT_REMOVED_BY_ADMIN"), eq(ADMIN_ID), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("publicReason", "SUSPECTED_FRAUD")
                .containsEntry("internalNote", "signalé par Awa Ndiaye, ticket #4821");
    }

    @Test
    @DisplayName("removeByAdmin : le voyageur reçoit le motif catalogué, JAMAIS la note interne")
    void removeByAdmin_notifiesOwnerWithoutLeakingTheInternalNote() {
        when(announcementRepository.findByIdForUpdate(ANN_ID)).thenReturn(Optional.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANN_ID), anyList())).thenReturn(false);
        when(announcementRepository.save(any())).thenReturn(announcement);

        service.removeByAdmin(ANN_ID, ADMIN_ID, AnnouncementRemovalReason.SUSPECTED_FRAUD,
                "signalé par Awa Ndiaye, ticket #4821");

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationDispatcher).notifyUser(eq(OWNER_ID), anyString(), bodyCaptor.capture(), anyMap());
        assertThat(bodyCaptor.getValue())
                .contains(AnnouncementRemovalReason.SUSPECTED_FRAUD.publicLabel())
                // Le cœur du test : la personne sanctionnée ne doit apprendre ni qui l'a
                // signalée, ni le numéro du ticket support.
                .doesNotContain("Awa Ndiaye")
                .doesNotContain("4821");
    }

    @Test
    @DisplayName("removeByAdmin : une note interne absente ne casse rien")
    void removeByAdmin_withoutInternalNote() {
        when(announcementRepository.findByIdForUpdate(ANN_ID)).thenReturn(Optional.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANN_ID), anyList())).thenReturn(false);
        when(announcementRepository.save(any())).thenReturn(announcement);

        service.removeByAdmin(ANN_ID, ADMIN_ID, AnnouncementRemovalReason.DUPLICATE, null);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationDispatcher).notifyUser(eq(OWNER_ID), anyString(), bodyCaptor.capture(), anyMap());
        assertThat(bodyCaptor.getValue()).contains(AnnouncementRemovalReason.DUPLICATE.publicLabel());
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

        when(announcementRepository.findByIdForUpdate(ANN_ID)).thenReturn(Optional.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANN_ID), anyList())).thenReturn(false);
        when(bidRepository.findByAnnouncementIdAndStatusIn(
                eq(ANN_ID), eq(LIQUIDATABLE_STATUSES)))
                .thenReturn(List.of(pendingBid, escrowedBid));
        when(announcementRepository.save(any())).thenReturn(announcement);

        service.removeByAdmin(ANN_ID, ADMIN_ID, AnnouncementRemovalReason.SUSPECTED_FRAUD, "note interne");

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
    @DisplayName("removeByAdmin (Critical A, round 3) : liquide aussi AWAITING_PAYMENT " +
            "(PaymentIntent Stripe déjà créé) et NEGOTIATING (fermé en NEGOTIATION_CLOSED, " +
            "jamais REJECTED — sinon réapparaît dans « Mes envois » et fausse le taux " +
            "d'acceptation du voyageur, cf. BidNegotiationService#closeThread)")
    void removeByAdmin_liquidatesAwaitingPaymentAndNegotiatingBids() throws Exception {
        UUID awaitingPaymentBidId = UUID.randomUUID();
        UUID awaitingPaymentSenderId = UUID.randomUUID();
        UUID negotiatingBidId = UUID.randomUUID();
        UUID negotiatingSenderId = UUID.randomUUID();

        BidEntity awaitingPaymentBid = new BidEntity();
        setId(awaitingPaymentBid, awaitingPaymentBidId);
        setField(awaitingPaymentBid, "senderId", awaitingPaymentSenderId);
        setField(awaitingPaymentBid, "status", BidStatus.AWAITING_PAYMENT);

        BidEntity negotiatingBid = new BidEntity();
        setId(negotiatingBid, negotiatingBidId);
        setField(negotiatingBid, "senderId", negotiatingSenderId);
        setField(negotiatingBid, "status", BidStatus.NEGOTIATING);

        when(announcementRepository.findByIdForUpdate(ANN_ID)).thenReturn(Optional.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANN_ID), anyList())).thenReturn(false);
        when(bidRepository.findByAnnouncementIdAndStatusIn(eq(ANN_ID), eq(LIQUIDATABLE_STATUSES)))
                .thenReturn(List.of(awaitingPaymentBid, negotiatingBid));
        when(announcementRepository.save(any())).thenReturn(announcement);

        service.removeByAdmin(ANN_ID, ADMIN_ID, AnnouncementRemovalReason.SUSPECTED_FRAUD, "note interne");

        // AWAITING_PAYMENT : un vrai "colis" (le PaymentIntent Stripe existe déjà côté
        // BidCheckoutService.checkout) → REJECTED, motif technique, comme PENDING/ESCROWED.
        assertThat(awaitingPaymentBid.getStatus()).isEqualTo(BidStatus.REJECTED);
        assertThat(awaitingPaymentBid.getRejectionReason()).isEqualTo(BidEntity.REJECTION_ANNOUNCEMENT_DELETED);

        // NEGOTIATING : jamais une réservation → NEGOTIATION_CLOSED, PAS REJECTED, et PAS
        // de rejectionReason (même contrat que BidNegotiationService#closeThread).
        assertThat(negotiatingBid.getStatus()).isEqualTo(BidStatus.NEGOTIATION_CLOSED);
        assertThat(negotiatingBid.getRejectionReason()).isNull();

        verify(bidRepository).save(awaitingPaymentBid);
        verify(bidRepository).save(negotiatingBid);

        // BidRejectedEvent publié pour les deux — RefundProcessor no-op proprement pour
        // NEGOTIATING (aucun PaymentEntity encore créé), mais l'expéditeur est notifié.
        ArgumentCaptor<BidRejectedEvent> eventCaptor = ArgumentCaptor.forClass(BidRejectedEvent.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(BidRejectedEvent::getBidId)
                .containsExactlyInAnyOrder(awaitingPaymentBidId, negotiatingBidId);
        assertThat(eventCaptor.getAllValues())
                .allSatisfy(e -> assertThat(e.isRematchEligible()).isFalse());
    }

    @Test
    @DisplayName("removeByAdmin : publie BidRejectedEvent pour chaque bid liquidé — " +
            "setStatus(REJECTED) seul ne rembourse rien, seul l'event déclenche RefundProcessor (Critical, round 2)")
    void removeByAdmin_publishesBidRejectedEventForLiquidatedBids() throws Exception {
        UUID escrowedSenderId = UUID.randomUUID();
        UUID escrowedBidId = UUID.randomUUID();

        BidEntity escrowedBid = new BidEntity();
        setId(escrowedBid, escrowedBidId);
        setField(escrowedBid, "senderId", escrowedSenderId);
        setField(escrowedBid, "status", BidStatus.PAYMENT_ESCROWED);

        when(announcementRepository.findByIdForUpdate(ANN_ID)).thenReturn(Optional.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANN_ID), anyList())).thenReturn(false);
        when(bidRepository.findByAnnouncementIdAndStatusIn(
                eq(ANN_ID), eq(LIQUIDATABLE_STATUSES)))
                .thenReturn(List.of(escrowedBid));
        when(announcementRepository.save(any())).thenReturn(announcement);

        service.removeByAdmin(ANN_ID, ADMIN_ID, AnnouncementRemovalReason.SUSPECTED_FRAUD, "note interne");

        ArgumentCaptor<BidRejectedEvent> eventCaptor = ArgumentCaptor.forClass(BidRejectedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        BidRejectedEvent published = eventCaptor.getValue();
        assertThat(published.getBidId()).isEqualTo(escrowedBidId);
        assertThat(published.getSenderId()).isEqualTo(escrowedSenderId);
        assertThat(published.getReason()).isEqualTo(BidEntity.REJECTION_ANNOUNCEMENT_DELETED);
        assertThat(published.getAnnouncementId()).isEqualTo(ANN_ID);
        // Délibéré : pas de rematch automatique après une décision de modération.
        assertThat(published.isRematchEligible()).isFalse();
    }

    @Test
    @DisplayName("removeByAdmin : refusé si des bids acceptés (ou au-delà) sont en cours")
    void removeByAdmin_rejectedWhenAcceptedBidsExist() {
        when(announcementRepository.findByIdForUpdate(ANN_ID)).thenReturn(Optional.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANN_ID), anyList())).thenReturn(true);

        assertThatThrownBy(() -> service.removeByAdmin(ANN_ID, ADMIN_ID, AnnouncementRemovalReason.SUSPECTED_FRAUD, "note interne"))
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
        when(announcementRepository.findByIdForUpdate(ANN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeByAdmin(ANN_ID, ADMIN_ID, AnnouncementRemovalReason.SUSPECTED_FRAUD, "note interne"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException y = (YadonyBusinessException) e;
                    assertThat(y.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(y.getErrorCode()).isEqualTo("announcement-not-found");
                });
    }

    @Test
    @DisplayName("removeByAdmin : mémorise le statut d'origine avant de retirer")
    void removeByAdmin_memorizesPreviousStatus() throws Exception {
        setField(announcement, "status", AnnouncementStatus.COMPLETED);
        when(announcementRepository.findByIdForUpdate(ANN_ID)).thenReturn(Optional.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANN_ID), anyList())).thenReturn(false);
        when(announcementRepository.save(any())).thenReturn(announcement);

        service.removeByAdmin(ANN_ID, ADMIN_ID, AnnouncementRemovalReason.SUSPECTED_FRAUD, "note interne");

        assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.REMOVED_BY_ADMIN);
        assertThat(announcement.getStatusBeforeRemoval()).isEqualTo(AnnouncementStatus.COMPLETED);
    }

    @Test
    @DisplayName("restoreByAdmin : une annonce COMPLETED retirée redevient COMPLETED, jamais ACTIVE")
    void restoreByAdmin_restoresMemorizedStatus() throws Exception {
        setField(announcement, "status", AnnouncementStatus.REMOVED_BY_ADMIN);
        announcement.setStatusBeforeRemoval(AnnouncementStatus.COMPLETED);
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement));
        when(announcementRepository.save(any())).thenReturn(announcement);

        AnnouncementEntity result = service.restoreByAdmin(ANN_ID, ADMIN_ID);

        assertThat(result.getStatus()).isEqualTo(AnnouncementStatus.COMPLETED);
        assertThat(result.getStatusBeforeRemoval()).isNull();
    }

    @Test
    @DisplayName("restoreByAdmin : statut d'origine inconnu (ligne retirée avant V220) → ACTIVE")
    void restoreByAdmin_withoutMemorizedStatus_fallsBackToActive() throws Exception {
        setField(announcement, "status", AnnouncementStatus.REMOVED_BY_ADMIN);
        announcement.setStatusBeforeRemoval(null);
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement));
        when(announcementRepository.save(any())).thenReturn(announcement);

        AnnouncementEntity result = service.restoreByAdmin(ANN_ID, ADMIN_ID);

        assertThat(result.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
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
