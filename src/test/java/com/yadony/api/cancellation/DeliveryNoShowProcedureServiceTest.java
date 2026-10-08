package com.yadony.api.cancellation;

import com.yadony.api.calls.CallRepository;
import com.yadony.api.cancellation.dto.DeliveryNoShowProcedureResponse;
import com.yadony.api.cancellation.events.DeliveryRetryAppointmentSetEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.messaging.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Procédure « destinataire absent » (FLUTTER-E2) : attente, preuve de contact, garde, nouveau RDV. */
@ExtendWith(MockitoExtension.class)
class DeliveryNoShowProcedureServiceTest {

    @Mock CancellationRepository cancellationRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock CallRepository callRepository;
    @Mock ConversationRepository conversationRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    static final LocalDateTime NOW_LDT = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    static final UUID BID_ID = UUID.randomUUID();
    static final UUID SENDER_ID = UUID.randomUUID();
    static final UUID TRAVELER_ID = UUID.randomUUID();
    static final UUID ANN_ID = UUID.randomUUID();

    DeliveryNoShowProcedureService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryNoShowProcedureService(cancellationRepository, bidRepository, announcementRepository,
                callRepository, conversationRepository, auditService, eventPublisher,
                new DeliveryNoShowProperties(120, 7), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private BidEntity bid(BidStatus status, LocalDateTime arrivedAt) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", BID_ID);
        b.setSenderId(SENDER_ID);
        b.setAnnouncementId(ANN_ID);
        b.setStatus(status);
        b.setArrivedAt(arrivedAt);
        return b;
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANN_ID);
        a.setTravelerId(TRAVELER_ID);
        return a;
    }

    private CancellationEntity report(CancellationStatus status, OffsetDateTime holdUntil) {
        CancellationEntity c = new CancellationEntity();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setBidId(BID_ID);
        c.setScope(CancellationScope.DELIVERY);
        c.setReason(DeliveryNoShowTypes.REASON_RECIPIENT_NO_SHOW);
        c.setNoShowStatus(status);
        c.setHoldUntil(holdUntil);
        return c;
    }

    private static String code(Throwable t) {
        return ((YadonyBusinessException) t).getErrorCode();
    }

    // ── Préalables du signalement ──

    @Test
    void preconditions_arriveeNonDeclaree_409() {
        assertThatThrownBy(() -> service.checkReportPreconditions(bid(BidStatus.IN_TRANSIT, null), TRAVELER_ID, true))
                .satisfies(t -> {
                    assertThat(code(t)).isEqualTo("delivery-noshow-arrival-not-declared");
                    assertThat(((YadonyBusinessException) t).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void preconditions_attenteNonEcoulee_422AvecHeureDeDisponibilite() {
        BidEntity b = bid(BidStatus.ARRIVED, NOW_LDT.minusMinutes(119));
        assertThatThrownBy(() -> service.checkReportPreconditions(b, TRAVELER_ID, true))
                .satisfies(t -> {
                    assertThat(code(t)).isEqualTo("delivery-noshow-wait-not-elapsed");
                    assertThat(((YadonyBusinessException) t).getProperties().get("availableAt"))
                            .isEqualTo(NOW_LDT.plusMinutes(1).atOffset(ZoneOffset.UTC).toString());
                });
        verifyNoInteractions(cancellationRepository);
    }

    @Test
    void preconditions_sansPreuveDeContact_422() {
        BidEntity b = bid(BidStatus.ARRIVED, NOW_LDT.minusMinutes(120));
        assertThatThrownBy(() -> service.checkReportPreconditions(b, TRAVELER_ID, true))
                .satisfies(t -> assertThat(code(t)).isEqualTo("delivery-noshow-contact-required"));
    }

    @Test
    void preconditions_appelPresentMaisCaseNonCochee_422() {
        BidEntity b = bid(BidStatus.ARRIVED, NOW_LDT.minusHours(3));
        when(callRepository.existsByBidIdAndCallerIdAndCreatedAtGreaterThanEqual(BID_ID, TRAVELER_ID, b.getArrivedAt()))
                .thenReturn(true);
        assertThatThrownBy(() -> service.checkReportPreconditions(b, TRAVELER_ID, false))
                .satisfies(t -> assertThat(code(t)).isEqualTo("delivery-noshow-confirmation-required"));
    }

    @Test
    void preconditions_appel_okEtGardeDeSeptJours() {
        BidEntity b = bid(BidStatus.ARRIVED, NOW_LDT.minusHours(3));
        when(callRepository.existsByBidIdAndCallerIdAndCreatedAtGreaterThanEqual(BID_ID, TRAVELER_ID, b.getArrivedAt()))
                .thenReturn(true);

        var p = service.checkReportPreconditions(b, TRAVELER_ID, true);

        assertThat(p.contactProof()).isEqualTo("CALL");
        assertThat(p.confirmedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(p.holdUntil()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(7));
        verifyNoInteractions(conversationRepository);
    }

    @Test
    void preconditions_messageSeul_ok() {
        BidEntity b = bid(BidStatus.ARRIVED, NOW_LDT.minusHours(3));
        when(conversationRepository.existsTravelerMessageSince(BID_ID, b.getArrivedAt())).thenReturn(true);

        assertThat(service.checkReportPreconditions(b, TRAVELER_ID, true).contactProof()).isEqualTo("MESSAGE");
    }

    /** Colis arrivé avant V300 : pas d'arrived_at, on retombe sur la dernière modification du bid. */
    @Test
    void preconditions_sansArrivedAt_retombeSurUpdatedAt() {
        BidEntity b = bid(BidStatus.ARRIVED, null);
        ReflectionTestUtils.setField(b, "updatedAt", NOW_LDT.minusMinutes(30));
        assertThatThrownBy(() -> service.checkReportPreconditions(b, TRAVELER_ID, true))
                .satisfies(t -> assertThat(code(t)).isEqualTo("delivery-noshow-wait-not-elapsed"));
    }

    @Test
    void evaluate_sansAucunRepere_attenteNonEcoulee() {
        var e = service.evaluate(bid(BidStatus.ARRIVED, null), TRAVELER_ID);
        assertThat(e.waitElapsed()).isFalse();
        assertThat(e.availableAt()).isNull();
    }

    // ── État de la procédure ──

    @Test
    void getProcedure_voyageurAvantSignalement_peutSignaler() {
        BidEntity b = bid(BidStatus.ARRIVED, NOW_LDT.minusHours(3));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(b));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY)).thenReturn(Optional.empty());
        when(callRepository.existsByBidIdAndCallerIdAndCreatedAtGreaterThanEqual(any(), any(), any())).thenReturn(true);

        DeliveryNoShowProcedureResponse r = service.getProcedure(BID_ID, TRAVELER_ID);

        assertThat(r.role()).isEqualTo("TRAVELER");
        assertThat(r.canReport()).isTrue();
        assertThat(r.reported()).isFalse();
        assertThat(r.contactProof()).isEqualTo("CALL");
        assertThat(r.waitElapsed()).isTrue();
        assertThat(r.minWaitMinutes()).isEqualTo(120);
    }

    @Test
    void getProcedure_expediteurPendantLaGarde_peutFixerUnRdv() {
        BidEntity b = bid(BidStatus.ARRIVED, NOW_LDT.minusHours(3));
        CancellationEntity c = report(CancellationStatus.CONFIRMED, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(5));
        c.setContactProof("CALL");
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(b));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY)).thenReturn(Optional.of(c));

        DeliveryNoShowProcedureResponse r = service.getProcedure(BID_ID, SENDER_ID);

        assertThat(r.role()).isEqualTo("SENDER");
        assertThat(r.reported()).isTrue();
        assertThat(r.canReport()).isFalse();
        assertThat(r.canSetRetryAppointment()).isTrue();
        assertThat(r.contactProof()).isNull();
        assertThat(r.holdUntil()).isEqualTo(c.getHoldUntil());
    }

    @Test
    void getProcedure_colisNonReclame_plusDeRdv() {
        BidEntity b = bid(BidStatus.ARRIVED, NOW_LDT.minusDays(8));
        CancellationEntity c = report(CancellationStatus.CONFIRMED, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(1));
        c.setUnclaimedAtForTest(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(b));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY)).thenReturn(Optional.of(c));

        DeliveryNoShowProcedureResponse r = service.getProcedure(BID_ID, SENDER_ID);

        assertThat(r.unclaimedAt()).isNotNull();
        assertThat(r.canSetRetryAppointment()).isFalse();
    }

    @Test
    void getProcedure_tiers_403() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid(BidStatus.ARRIVED, NOW_LDT)));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        assertThatThrownBy(() -> service.getProcedure(BID_ID, UUID.randomUUID()))
                .satisfies(t -> assertThat(code(t)).isEqualTo("forbidden"));
    }

    @Test
    void getProcedure_colisEnTransit_neCalculePasLAttente() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid(BidStatus.IN_TRANSIT, null)));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY)).thenReturn(Optional.empty());

        DeliveryNoShowProcedureResponse r = service.getProcedure(BID_ID, TRAVELER_ID);

        assertThat(r.canReport()).isFalse();
        assertThat(r.reportAvailableAt()).isNull();
        verifyNoInteractions(callRepository);
    }

    // ── Nouveau rendez-vous ──

    @Test
    void retryAppointment_ok_enregistreAuditEtNotifie() {
        BidEntity b = bid(BidStatus.ARRIVED, NOW_LDT.minusDays(1));
        OffsetDateTime hold = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(6);
        CancellationEntity c = report(CancellationStatus.PENDING_CONFIRMATION, hold);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(b));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(announcement()));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY)).thenReturn(Optional.of(c));

        OffsetDateTime at = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(2);
        service.setRetryAppointment(BID_ID, SENDER_ID, at, "  Devant la gare  ");

        assertThat(c.getRetryAppointmentAt()).isEqualTo(at);
        assertThat(c.getRetryAppointmentNote()).isEqualTo("Devant la gare");
        verify(cancellationRepository).save(c);
        verify(auditService).log(eq("CANCELLATION"), eq(c.getId()), eq("DELIVERY_RETRY_APPOINTMENT_SET"), eq(SENDER_ID), any());
        verify(eventPublisher).publishEvent(new DeliveryRetryAppointmentSetEvent(BID_ID, SENDER_ID, TRAVELER_ID, at));
    }

    @Test
    void retryAppointment_apresLaFinDeGarde_422() {
        BidEntity b = bid(BidStatus.ARRIVED, NOW_LDT.minusDays(1));
        OffsetDateTime hold = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(1);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(b));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY))
                .thenReturn(Optional.of(report(CancellationStatus.CONFIRMED, hold)));

        assertThatThrownBy(() -> service.setRetryAppointment(BID_ID, SENDER_ID, hold.plusMinutes(1), null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("retry-appointment-out-of-hold"));
        assertThatThrownBy(() -> service.setRetryAppointment(BID_ID, SENDER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(1), null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("retry-appointment-out-of-hold"));
        verify(cancellationRepository, never()).save(any());
    }

    @Test
    void retryAppointment_signalementConteste_409() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid(BidStatus.ARRIVED, NOW_LDT)));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY))
                .thenReturn(Optional.of(report(CancellationStatus.CONTESTED,
                        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(3))));

        assertThatThrownBy(() -> service.setRetryAppointment(BID_ID, SENDER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(1), null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("retry-appointment-closed"));
    }

    @Test
    void retryAppointment_signalementSansGarde_409() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid(BidStatus.ARRIVED, NOW_LDT)));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY))
                .thenReturn(Optional.of(report(CancellationStatus.PENDING_CONFIRMATION, null)));

        assertThatThrownBy(() -> service.setRetryAppointment(BID_ID, SENDER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(1), null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("retry-appointment-closed"));
    }

    @Test
    void retryAppointment_pasLExpediteur_403() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid(BidStatus.ARRIVED, NOW_LDT)));
        assertThatThrownBy(() -> service.setRetryAppointment(BID_ID, TRAVELER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(1), null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("forbidden"));
    }

    @Test
    void retryAppointment_sansSignalement_404() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid(BidStatus.ARRIVED, NOW_LDT)));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setRetryAppointment(BID_ID, SENDER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(1), null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("delivery-noshow-not-found"));
    }

    @Test
    void bidIntrouvable_404() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProcedure(BID_ID, SENDER_ID))
                .satisfies(t -> assertThat(code(t)).isEqualTo("bid-not-found"));
    }

    @Test
    void proprietes_valeursParDefautPrudentes() {
        DeliveryNoShowProperties p = new DeliveryNoShowProperties(0, 0);
        assertThat(p.minWaitMinutes()).isEqualTo(120);
        assertThat(p.holdDays()).isEqualTo(7);
    }
}
