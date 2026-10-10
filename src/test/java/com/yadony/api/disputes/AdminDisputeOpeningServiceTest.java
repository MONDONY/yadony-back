package com.yadony.api.disputes;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.dto.AdminOpenDisputeRequest;
import com.yadony.api.disputes.dto.AdminOpenDisputeResponse;
import com.yadony.api.disputes.events.DisputeOpenedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminDisputeOpeningServiceTest {

    @Mock DisputeRepository disputeRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    private AdminDisputeOpeningService service;
    private final UUID bidId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private final UUID disputeId = UUID.randomUUID();
    private BidEntity bid;

    private static final AdminOpenDisputeRequest REQUEST = new AdminOpenDisputeRequest(
            DisputeParty.SENDER, AdminDisputeReason.PARCEL_DAMAGED, "  Colis arrivé ouvert, photos à l'appui  ");

    @BeforeEach
    void setUp() {
        service = new AdminDisputeOpeningService(disputeRepository, bidRepository, announcementRepository,
                paymentRepository, auditService, eventPublisher);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setSenderId(senderId);
        bid.setAnnouncementId(announcementId);
        bid.setStatus(BidStatus.ARRIVED);
        AnnouncementEntity ann = new AnnouncementEntity();
        ann.setTravelerId(travelerId);
        lenient().when(bidRepository.findByIdForUpdate(bidId)).thenReturn(Optional.of(bid));
        lenient().when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        lenient().when(paymentRepository.findForBid(bidId)).thenReturn(Optional.empty());
        lenient().when(disputeRepository.findByBidIdAndType(any(), any())).thenReturn(Optional.empty());
        lenient().when(disputeRepository.save(any())).thenAnswer(inv -> {
            DisputeEntity d = inv.getArgument(0);
            ReflectionTestUtils.setField(d, "id", disputeId);
            return d;
        });
    }

    private void payment(PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setStatus(status);
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.of(p));
    }

    @Test
    void ouvre_auNomDeLExpediteur_gelLeVersement_notifieEtAudite() {
        payment(PaymentStatus.ESCROW);

        AdminOpenDisputeResponse r = service.open(bidId, adminId, REQUEST);

        assertThat(r.disputeId()).isEqualTo(disputeId);
        assertThat(r.type()).isEqualTo("ADMIN_PARCEL_DAMAGED");
        assertThat(r.status()).isEqualTo("OPEN");
        assertThat(r.payoutFrozen()).isTrue();
        assertThat(r.paymentStatus()).isEqualTo("ESCROW");
        assertThat(r.openedOnBehalfOf()).isEqualTo("SENDER");

        ArgumentCaptor<DisputeEntity> saved = ArgumentCaptor.forClass(DisputeEntity.class);
        verify(disputeRepository).save(saved.capture());
        DisputeEntity d = saved.getValue();
        assertThat(d.getReporterId()).isEqualTo(senderId);
        assertThat(d.getSenderId()).isEqualTo(senderId);
        assertThat(d.getTravelerId()).isEqualTo(travelerId);
        assertThat(d.isRefundFrozen()).isTrue();
        assertThat(d.getReason()).isEqualTo("Colis arrivé ouvert, photos à l'appui");

        verify(auditService).log(eq("DISPUTE"), eq(disputeId), eq("ADMIN_DISPUTE_OPENED"), eq(adminId),
                argThat((Map<String, Object> m) -> "PARCEL_DAMAGED".equals(m.get("reason"))
                        && "SENDER".equals(m.get("openedOnBehalfOf"))
                        && Boolean.TRUE.equals(m.get("payoutFrozen"))));
        verify(eventPublisher).publishEvent(argThat((Object e) -> e instanceof DisputeOpenedEvent ev
                && ev.getBidId().equals(bidId) && "ADMIN_PARCEL_DAMAGED".equals(ev.getType())
                && ev.getTravelerId().equals(travelerId)));
    }

    @Test
    void auNomDuVoyageur_sansPaiement_rienAGeler() {
        AdminOpenDisputeResponse r = service.open(bidId, adminId, new AdminOpenDisputeRequest(
                DisputeParty.TRAVELER, AdminDisputeReason.OTHER, "Le destinataire refuse de payer la douane"));

        assertThat(r.payoutFrozen()).isFalse();
        assertThat(r.paymentStatus()).isNull();
        ArgumentCaptor<DisputeEntity> saved = ArgumentCaptor.forClass(DisputeEntity.class);
        verify(disputeRepository).save(saved.capture());
        assertThat(saved.getValue().getReporterId()).isEqualTo(travelerId);
    }

    @Test
    void voyageurDejaPaye_409_messageClair() {
        payment(PaymentStatus.RELEASED);

        assertThatThrownBy(() -> service.open(bidId, adminId, REQUEST))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("payment-already-released");
                    assertThat(e.getMessage()).contains("déjà été payé");
                });
        verify(disputeRepository, never()).save(any());
    }

    @Test
    void litigeDejaOuvert_409() {
        when(disputeRepository.existsByBidIdAndStatusNot(bidId, "RESOLVED")).thenReturn(true);
        assertConflict("dispute-already-open");
    }

    @Test
    void memeMotifDejaTraite_409() {
        when(disputeRepository.findByBidIdAndType(bidId, "ADMIN_PARCEL_DAMAGED"))
                .thenReturn(Optional.of(new DisputeEntity()));
        assertConflict("dispute-already-exists");
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"PENDING", "AWAITING_PAYMENT", "CANCELLED", "REJECTED",
            "EXPIRED", "NEGOTIATING", "NEGOTIATION_CLOSED"})
    void colisSansTransactionEngagee_409(BidStatus status) {
        bid.setStatus(status);
        assertConflict("bid-not-disputable");
    }

    @Test
    void trajetDisparu_409() {
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());
        assertConflict("traveler-not-found");
    }

    @Test
    void colisIntrouvable_404() {
        when(bidRepository.findByIdForUpdate(bidId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.open(bidId, adminId, REQUEST))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void assertConflict(String code) {
        assertThatThrownBy(() -> service.open(bidId, adminId, REQUEST))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo(code);
                });
        verify(disputeRepository, never()).save(any());
        verifyNoInteractions(auditService, eventPublisher);
    }
}
