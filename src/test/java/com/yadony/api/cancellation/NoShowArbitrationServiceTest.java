package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.cancellation.events.NoShowAdminDecisionEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.events.DisputeOpenedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NoShowArbitrationServiceTest {

    @Mock CancellationRepository cancellationRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    private NoShowArbitrationService service;
    private DeliveryNoShowUncontestedScheduler scheduler;

    static final UUID ID = UUID.randomUUID();
    static final UUID BID_ID = UUID.randomUUID();
    static final UUID ANN_ID = UUID.randomUUID();
    static final UUID SENDER_ID = UUID.randomUUID();
    static final UUID TRAVELER_ID = UUID.randomUUID();
    static final UUID ADMIN_ID = UUID.randomUUID();
    static final String MOTIF = "Photos horodatées du voyageur au point de remise";

    @BeforeEach
    void setUp() {
        // Le vrai scheduler (sur les mêmes mocks) : la confirmation DELIVERY doit réutiliser
        // SA logique d'ouverture de litige, pas une copie.
        scheduler = new DeliveryNoShowUncontestedScheduler(
                cancellationRepository, bidRepository, announcementRepository, eventPublisher, auditService);
        service = new NoShowArbitrationService(cancellationRepository, bidRepository,
                announcementRepository, auditService, eventPublisher, scheduler);
    }

    private CancellationEntity row(CancellationScope scope, String reason, CancellationStatus status) {
        CancellationEntity c = new CancellationEntity();
        ReflectionTestUtils.setField(c, "id", ID);
        c.setBidId(BID_ID);
        c.setScope(scope);
        c.setReason(reason);
        c.setNoShowStatus(status);
        c.setCancelledBy(scope == CancellationScope.HANDOVER ? TRAVELER_ID : TRAVELER_ID);
        c.setContestationDeadline(OffsetDateTime.now().plusHours(3));
        return c;
    }

    private BidEntity stubBidAndAnnouncement() {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", BID_ID);
        bid.setSenderId(SENDER_ID);
        bid.setAnnouncementId(ANN_ID);
        bid.setStatus(BidStatus.ACCEPTED);
        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", ANN_ID);
        ann.setTravelerId(TRAVELER_ID);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(ann));
        return bid;
    }

    private void stubRow(CancellationEntity c) {
        when(cancellationRepository.findById(ID)).thenReturn(Optional.of(c));
    }

    private NoShowAdminDecisionEvent decisionEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .filter(NoShowAdminDecisionEvent.class::isInstance)
                .map(NoShowAdminDecisionEvent.class::cast)
                .findFirst().orElseThrow();
    }

    @Nested
    class Confirmer {

        @Test
        void handoverEnAttente_confirmeParLEvenementExistant_etTraceLaDecision() {
            CancellationEntity c = row(CancellationScope.HANDOVER, "SENDER_NO_SHOW",
                    CancellationStatus.PENDING_CONFIRMATION);
            stubRow(c);
            stubBidAndAnnouncement();

            CancellationEntity result = service.confirm(ID, ADMIN_ID, MOTIF);

            assertThat(result.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
            assertThat(result.getAdminDecision()).isEqualTo(NoShowAdminDecision.CONFIRMED);
            assertThat(result.getDecidedByAdminId()).isEqualTo(ADMIN_ID);
            assertThat(result.getDecidedAt()).isNotNull();
            assertThat(result.getDecisionReason()).isEqualTo(MOTIF);
            verify(cancellationRepository).save(c);
            verify(eventPublisher).publishEvent(
                    new CancellationConfirmedEvent(BID_ID, ID, CancellationReason.SENDER_NO_SHOW));
            verify(auditService).log("CANCELLATION", ID, "NOSHOW_CONFIRMED_BY_ADMIN", ADMIN_ID,
                    Map.of("bidId", BID_ID.toString(), "scope", "HANDOVER", "reason", "SENDER_NO_SHOW",
                            "decisionReason", MOTIF, "previousStatus", "PENDING_CONFIRMATION"));

            NoShowAdminDecisionEvent event = decisionEvent();
            assertThat(event.decision()).isEqualTo(NoShowAdminDecision.CONFIRMED);
            assertThat(event.senderId()).isEqualTo(SENDER_ID);
            assertThat(event.travelerId()).isEqualTo(TRAVELER_ID);
            assertThat(event.adminId()).isEqualTo(ADMIN_ID);
            assertThat(event.scope()).isEqualTo(CancellationScope.HANDOVER);
            // La décision tranche aussi le litige de contestation s'il existe.
            assertThat(event.disputeTypesToClose()).containsExactly("SENDER_NO_SHOW_CONTESTED");
        }

        @Test
        void handoverConteste_estConfirmableEtFermeLeLitigeLie() {
            CancellationEntity c = row(CancellationScope.HANDOVER, "SENDER_NO_SHOW", CancellationStatus.CONTESTED);
            stubRow(c);
            stubBidAndAnnouncement();

            service.confirm(ID, ADMIN_ID, MOTIF);

            assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
            verify(eventPublisher).publishEvent(any(CancellationConfirmedEvent.class));
            assertThat(decisionEvent().disputeTypesToClose()).containsExactly("SENDER_NO_SHOW_CONTESTED");
        }

        @Test
        void livraisonEnAttente_ouvreLeLitigeCommeLeScheduler_sansEffetFinancier() {
            CancellationEntity c = row(CancellationScope.DELIVERY, "RECIPIENT_NO_SHOW",
                    CancellationStatus.PENDING_CONFIRMATION);
            stubRow(c);
            stubBidAndAnnouncement();

            service.confirm(ID, ADMIN_ID, MOTIF);

            assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
            assertThat(c.getAdminDecision()).isEqualTo(NoShowAdminDecision.CONFIRMED);
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
            DisputeOpenedEvent opened = captor.getAllValues().stream()
                    .filter(DisputeOpenedEvent.class::isInstance).map(DisputeOpenedEvent.class::cast)
                    .findFirst().orElseThrow();
            assertThat(opened.getType()).isEqualTo("RECIPIENT_NO_SHOW");
            assertThat(opened.getSenderId()).isEqualTo(SENDER_ID);
            assertThat(opened.getTravelerId()).isEqualTo(TRAVELER_ID);
            assertThat(captor.getAllValues()).noneMatch(CancellationConfirmedEvent.class::isInstance);
            verify(auditService).log(eq("CANCELLATION"), eq(ID), eq("DELIVERY_NOSHOW_UNCONTESTED_DISPUTE_OPENED"),
                    any(), any());
            verify(auditService).log(eq("CANCELLATION"), eq(ID), eq("NOSHOW_CONFIRMED_BY_ADMIN"), eq(ADMIN_ID), any());
            // Le litige reste ouvert : l'admin y tranche la partie financière.
            assertThat(decisionEvent().disputeTypesToClose()).isEmpty();
        }

        @Test
        void livraisonContestee_reutiliseLeLitigeExistant() {
            CancellationEntity c = row(CancellationScope.DELIVERY, "TRAVELER_DELIVERY_NO_SHOW",
                    CancellationStatus.CONTESTED);
            stubRow(c);
            stubBidAndAnnouncement();

            service.confirm(ID, ADMIN_ID, MOTIF);

            assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
            verify(cancellationRepository).save(c);
            verify(eventPublisher, never()).publishEvent(any(DisputeOpenedEvent.class));
            verify(eventPublisher, never()).publishEvent(any(CancellationConfirmedEvent.class));
            assertThat(decisionEvent().disputeTypesToClose()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(value = CancellationStatus.class, names = {"CONFIRMED", "RESOLVED"})
        void dejaTranchee_409(CancellationStatus status) {
            stubRow(row(CancellationScope.HANDOVER, "SENDER_NO_SHOW", status));

            assertThatThrownBy(() -> service.confirm(ID, ADMIN_ID, MOTIF))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("noshow-already-decided");
                    });
            verify(cancellationRepository, never()).save(any());
            verifyNoInteractions(eventPublisher, auditService);
        }

        @Test
        void ligneInconnue_404() {
            when(cancellationRepository.findById(ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirm(ID, ADMIN_ID, MOTIF))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("noshow-not-found"));
        }

        @Test
        void annulationQuiNestPasUnNoShow_404() {
            stubRow(row(CancellationScope.HANDOVER, "TRIP_CANCELLED", CancellationStatus.PENDING_CONFIRMATION));

            assertThatThrownBy(() -> service.confirm(ID, ADMIN_ID, MOTIF))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        void bidIntrouvable_404() {
            stubRow(row(CancellationScope.HANDOVER, "SENDER_NO_SHOW", CancellationStatus.PENDING_CONFIRMATION));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirm(ID, ADMIN_ID, MOTIF))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("bid-not-found"));
        }

        @Test
        void annonceIntrouvable_voyageurInconnuMaisDecisionPrise() {
            CancellationEntity c = row(CancellationScope.HANDOVER, "SENDER_NO_SHOW",
                    CancellationStatus.PENDING_CONFIRMATION);
            stubRow(c);
            BidEntity bid = new BidEntity();
            bid.setSenderId(SENDER_ID);
            bid.setAnnouncementId(ANN_ID);
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.empty());

            service.confirm(ID, ADMIN_ID, MOTIF);

            assertThat(decisionEvent().travelerId()).isNull();
        }
    }

    @Nested
    class Rejeter {

        @Test
        void handoverEnAttente_classeLaDeclaration_sansToucherAuBid() {
            CancellationEntity c = row(CancellationScope.HANDOVER, "SENDER_NO_SHOW",
                    CancellationStatus.PENDING_CONFIRMATION);
            stubRow(c);
            BidEntity bid = stubBidAndAnnouncement();

            CancellationEntity result = service.reject(ID, ADMIN_ID, MOTIF);

            assertThat(result.getNoShowStatus()).isEqualTo(CancellationStatus.RESOLVED);
            assertThat(result.getAdminDecision()).isEqualTo(NoShowAdminDecision.REJECTED);
            assertThat(result.getDecidedByAdminId()).isEqualTo(ADMIN_ID);
            assertThat(result.getDecisionReason()).isEqualTo(MOTIF);
            assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
            verify(bidRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(CancellationConfirmedEvent.class));
            verify(eventPublisher, never()).publishEvent(any(DisputeOpenedEvent.class));
            verify(auditService).log("CANCELLATION", ID, "NOSHOW_REJECTED_BY_ADMIN", ADMIN_ID,
                    Map.of("bidId", BID_ID.toString(), "scope", "HANDOVER", "reason", "SENDER_NO_SHOW",
                            "decisionReason", MOTIF, "previousStatus", "PENDING_CONFIRMATION"));
            NoShowAdminDecisionEvent event = decisionEvent();
            assertThat(event.decision()).isEqualTo(NoShowAdminDecision.REJECTED);
            assertThat(event.disputeTypesToClose()).containsExactly("SENDER_NO_SHOW_CONTESTED");
        }

        @Test
        void livraisonContestee_fermeLesLitigesLies() {
            CancellationEntity c = row(CancellationScope.DELIVERY, "RECIPIENT_NO_SHOW", CancellationStatus.CONTESTED);
            stubRow(c);
            stubBidAndAnnouncement();

            service.reject(ID, ADMIN_ID, MOTIF);

            assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.RESOLVED);
            assertThat(decisionEvent().disputeTypesToClose())
                    .containsExactly("RECIPIENT_NO_SHOW_CONTESTED", "RECIPIENT_NO_SHOW");
        }

        @ParameterizedTest
        @EnumSource(value = CancellationStatus.class, names = {"CONFIRMED", "RESOLVED"})
        void dejaTranchee_409(CancellationStatus status) {
            stubRow(row(CancellationScope.DELIVERY, "RECIPIENT_NO_SHOW", status));

            assertThatThrownBy(() -> service.reject(ID, ADMIN_ID, MOTIF))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("noshow-already-decided"));
            verifyNoInteractions(eventPublisher, auditService);
        }
    }

    @Nested
    class AncienPointDEntree {

        @Test
        void enAttente_delegueALaConfirmationAdmin() {
            CancellationEntity c = row(CancellationScope.HANDOVER, "SENDER_NO_SHOW",
                    CancellationStatus.PENDING_CONFIRMATION);
            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));
            stubBidAndAnnouncement();

            service.confirmLegacyByBid(BID_ID, ADMIN_ID);

            assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
            assertThat(c.getAdminDecision()).isEqualTo(NoShowAdminDecision.CONFIRMED);
            assertThat(c.getDecisionReason()).isEqualTo(NoShowArbitrationService.LEGACY_DECISION_REASON);
            verify(eventPublisher).publishEvent(any(CancellationConfirmedEvent.class));
            verify(auditService).log(eq("CANCELLATION"), eq(ID), eq("NOSHOW_CONFIRMED_BY_ADMIN"), eq(ADMIN_ID), any());
        }

        @Test
        void dejaConfirmee_resteSansEffetCommeAvant() {
            CancellationEntity c = row(CancellationScope.HANDOVER, "SENDER_NO_SHOW", CancellationStatus.CONFIRMED);
            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));

            service.confirmLegacyByBid(BID_ID, ADMIN_ID);

            verify(cancellationRepository, never()).save(any());
            verifyNoInteractions(eventPublisher, auditService);
        }

        @Test
        void autreMotifHandover_sansEffet() {
            CancellationEntity c = row(CancellationScope.HANDOVER, "SENDER_CANCEL_AFTER_HANDOVER",
                    CancellationStatus.PENDING_CONFIRMATION);
            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));

            service.confirmLegacyByBid(BID_ID, ADMIN_ID);

            verifyNoInteractions(eventPublisher, auditService);
        }

        @Test
        void aucuneLigne_404CommeAvant() {
            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmLegacyByBid(BID_ID, ADMIN_ID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("cancellation-not-found"));
        }
    }

    @Test
    void motifsNoShow_listeExacte() {
        assertThat(NoShowReasons.ALL)
                .containsExactlyInAnyOrder("SENDER_NO_SHOW", "RECIPIENT_NO_SHOW", "TRAVELER_DELIVERY_NO_SHOW");
        assertThat(NoShowReasons.linkedDisputeTypes("TRAVELER_DELIVERY_NO_SHOW"))
                .containsExactly("TRAVELER_DELIVERY_NO_SHOW_CONTESTED", "TRAVELER_DELIVERY_NO_SHOW");
        assertThat(NoShowReasons.linkedDisputeTypes("OTHER")).isEmpty();
        assertThat(NoShowReasons.isNoShow(null)).isFalse();
        assertThat(List.of(NoShowAdminDecision.values())).hasSize(2);
    }
}
