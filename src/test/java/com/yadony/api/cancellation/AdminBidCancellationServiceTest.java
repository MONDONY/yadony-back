package com.yadony.api.cancellation;

import com.yadony.api.cancellation.dto.AdminBidCancelRequest;
import com.yadony.api.cancellation.dto.AdminBidCancelResponse;
import com.yadony.api.cancellation.events.AdminBidCancelledEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
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

import java.math.BigDecimal;
import java.time.LocalDate;
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
class AdminBidCancellationServiceTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock DisputeRepository disputeRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    private AdminBidCancellationService service;
    private final UUID bidId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private BidEntity bid;
    private AnnouncementEntity announcement;

    private static final AdminBidCancelRequest REQUEST =
            new AdminBidCancelRequest(AdminBidCancelReason.SENDER_REQUEST, "Demande écrite au support");

    @BeforeEach
    void setUp() {
        service = new AdminBidCancellationService(bidRepository, announcementRepository, paymentRepository,
                disputeRepository, auditService, eventPublisher);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setSenderId(senderId);
        bid.setAnnouncementId(announcementId);
        bid.setWeightKg(new BigDecimal("5"));
        bid.setCurrency("EUR");
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setStatus(BidStatus.ACCEPTED);
        announcement = new AnnouncementEntity();
        announcement.setTravelerId(travelerId);
        announcement.setDepartureDate(LocalDate.now().plusDays(5));
        announcement.setCapacityUnit(CapacityUnit.KG_EXACT);
        announcement.setAvailableKg(BigDecimal.ZERO);
        announcement.setStatus(AnnouncementStatus.FULL);
        lenient().when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        lenient().when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        lenient().when(paymentRepository.findForBid(bidId)).thenReturn(Optional.empty());
    }

    private PaymentEntity payment(PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setStatus(status);
        p.setAmount(new BigDecimal("42.00"));
        p.setCommissionAmount(new BigDecimal("5.04"));
        p.setCurrency("EUR");
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void colisPayeEnSequestre_annule_rembourseIntegralement_rendLaCapacite_previentLesParties() {
        payment(PaymentStatus.ESCROW);

        AdminBidCancelResponse r = service.cancel(bidId, adminId, REQUEST);

        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(r.alreadyCancelled()).isFalse();
        assertThat(r.refundRequested()).isTrue();
        assertThat(r.refundAmount()).isEqualByComparingTo("42.00");
        assertThat(r.previousStatus()).isEqualTo("ACCEPTED");
        assertThat(r.paymentStatus()).isEqualTo("ESCROW");
        assertThat(r.parcelWithTraveler()).isFalse();
        assertThat(announcement.getAvailableKg()).isEqualByComparingTo("5");
        assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        verify(announcementRepository).save(announcement);
        verify(bidRepository).save(bid);

        verify(auditService).log(eq("BID"), eq(bidId), eq("ADMIN_BID_CANCELLED"), eq(adminId),
                argThat((Map<String, Object> m) -> "SENDER_REQUEST".equals(m.get("reason"))
                        && "Demande écrite au support".equals(m.get("note"))
                        && Boolean.TRUE.equals(m.get("refundRequested"))));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());
        BidRejectedEvent rejected = (BidRejectedEvent) events.getAllValues().get(0);
        assertThat(rejected.getReason()).isEqualTo(BidRejectedEvent.REASON_CANCELLED_BY_ADMIN);
        assertThat(rejected.isRematchEligible()).isFalse();
        AdminBidCancelledEvent cancelled = (AdminBidCancelledEvent) events.getAllValues().get(1);
        assertThat(cancelled.senderId()).isEqualTo(senderId);
        assertThat(cancelled.travelerId()).isEqualTo(travelerId);
        assertThat(cancelled.adminId()).isEqualTo(adminId);
        assertThat(cancelled.refundRequested()).isTrue();
    }

    @Test
    void remboursementPartielDejaPasse_rendLeReste() {
        payment(PaymentStatus.ESCROW).setRefundedAmount(new BigDecimal("10.00"));

        assertThat(service.cancel(bidId, adminId, REQUEST).refundAmount()).isEqualByComparingTo("32.00");
    }

    @Test
    void paiementCarteNonConfirme_autorisationLevee_montantRenduNul() {
        bid.setStatus(BidStatus.PAYMENT_ESCROWED);
        payment(PaymentStatus.PENDING);

        AdminBidCancelResponse r = service.cancel(bidId, adminId, REQUEST);

        assertThat(r.refundRequested()).isTrue();
        assertThat(r.refundAmount()).isEqualByComparingTo("0");
        // PAYMENT_ESCROWED : la capacité n'a pas encore été prélevée.
        verify(announcementRepository, never()).save(any());
    }

    @Test
    void sansPaiement_aucunRemboursement() {
        bid.setStatus(BidStatus.PENDING);

        AdminBidCancelResponse r = service.cancel(bidId, adminId, REQUEST);

        assertThat(r.refundRequested()).isFalse();
        assertThat(r.paymentStatus()).isNull();
        assertThat(r.currency()).isEqualTo("EUR");
    }

    @Test
    void colisRemisAvantDepart_retourAOrganiser_capaciteRendue() {
        bid.setStatus(BidStatus.HANDED_OVER);
        payment(PaymentStatus.ESCROW);

        AdminBidCancelResponse r = service.cancel(bidId, adminId, REQUEST);

        assertThat(r.parcelWithTraveler()).isTrue();
        verify(announcementRepository).save(announcement);
    }

    @Test
    void colisEnTransit_trajetParti_capaciteNonRendue() {
        bid.setStatus(BidStatus.IN_TRANSIT);
        announcement.setDepartureDate(LocalDate.now().minusDays(2));
        payment(PaymentStatus.ESCROW);

        AdminBidCancelResponse r = service.cancel(bidId, adminId, REQUEST);

        assertThat(r.parcelWithTraveler()).isTrue();
        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        verify(announcementRepository, never()).save(any());
    }

    @Test
    void attenteMobileMoney_capaciteDejaReservee_rendue() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);

        service.cancel(bidId, adminId, REQUEST);

        verify(announcementRepository).save(announcement);
    }

    @Test
    void attenteCarte_aucuneCapaciteReservee() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);

        service.cancel(bidId, adminId, REQUEST);

        verify(announcementRepository, never()).save(any());
    }

    @Test
    void trajetSupprime_annulationQuandMeme() {
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        service.cancel(bidId, adminId, REQUEST);

        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        verify(eventPublisher).publishEvent(argThat((Object e) -> e instanceof AdminBidCancelledEvent ev && ev.travelerId() == null));
    }

    @Test
    void dejaAnnule_idempotent_rienNEstRefait() {
        bid.setStatus(BidStatus.CANCELLED);

        AdminBidCancelResponse r = service.cancel(bidId, adminId, REQUEST);

        assertThat(r.alreadyCancelled()).isTrue();
        assertThat(r.refundRequested()).isFalse();
        verifyNoInteractions(auditService, eventPublisher);
        verify(bidRepository, never()).save(any());
    }

    @Test
    void dejaAnnuleMaisPaiementEncoreEnSequestre_remboursementRelance() {
        bid.setStatus(BidStatus.CANCELLED);
        PaymentEntity p = payment(PaymentStatus.ESCROW);

        AdminBidCancelResponse r = service.cancel(bidId, adminId, REQUEST);

        assertThat(r.alreadyCancelled()).isTrue();
        assertThat(r.refundRequested()).isTrue();
        assertThat(r.refundAmount()).isEqualByComparingTo("42.00");
        verify(paymentRepository).lockIfEscrow(p.getId());
        verify(eventPublisher).publishEvent(argThat((Object e) -> e instanceof BidRejectedEvent ev
                && BidRejectedEvent.REASON_CANCELLED_BY_ADMIN.equals(ev.getReason())));
        verify(auditService).log(eq("BID"), eq(bidId), eq("ADMIN_BID_CANCEL_REFUND_RETRIED"), eq(adminId), any());
        verify(bidRepository, never()).save(any());
    }

    @Test
    void statutRelueSousVerrou_verseEntreTemps_409() {
        // Entité chargée encore ESCROW, mais un versement concurrent a commité RELEASED.
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        when(paymentRepository.findStatusById(p.getId())).thenReturn(Optional.of(PaymentStatus.RELEASED));

        assertConflict("payment-released");
        verify(paymentRepository).lockIfEscrow(p.getId());
    }

    @Test
    void colisLivre_409() {
        bid.setStatus(BidStatus.COMPLETED);
        assertConflict("bid-delivered");
    }

    @Test
    void negociation_409() {
        bid.setStatus(BidStatus.NEGOTIATING);
        assertConflict("bid-not-a-parcel");
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"REJECTED", "EXPIRED", "NO_SHOW", "PARCEL_REFUSED", "NEGOTIATION_CLOSED"})
    void colisDejaTermine_409(BidStatus status) {
        bid.setStatus(status);
        assertConflict("bid-already-closed");
    }

    @Test
    void voyageurDejaPaye_409() {
        payment(PaymentStatus.RELEASED);
        assertConflict("payment-released");
    }

    @Test
    void litigeBancaire_409() {
        payment(PaymentStatus.ESCROW).setDisputed(true);
        assertConflict("payment-disputed");
    }

    @Test
    void litigeOuvert_409() {
        when(disputeRepository.existsByBidIdAndStatusNot(bidId, "RESOLVED")).thenReturn(true);
        assertConflict("dispute-open");
    }

    @Test
    void motifAutreSansNote_422() {
        assertThatThrownBy(() -> service.cancel(bidId, adminId,
                new AdminBidCancelRequest(AdminBidCancelReason.OTHER, "  court ")))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("cancel-note-required");
                });
        verifyNoInteractions(bidRepository);
    }

    @Test
    void motifAutreAvecNote_accepte_noteNulleSinon() {
        service.cancel(bidId, adminId, new AdminBidCancelRequest(AdminBidCancelReason.OTHER,
                "Colis signalé en double par les deux parties"));
        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);

        bid.setStatus(BidStatus.ACCEPTED);
        service.cancel(bidId, adminId, new AdminBidCancelRequest(AdminBidCancelReason.DUPLICATE, null));
        verify(auditService).log(any(), any(), any(), any(), argThat((Map<String, Object> m) -> "".equals(m.get("note"))));
    }

    @Test
    void colisIntrouvable_404() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(bidId, adminId, REQUEST))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void assertConflict(String code) {
        assertThatThrownBy(() -> service.cancel(bidId, adminId, REQUEST))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo(code);
                });
        verify(bidRepository, never()).save(any());
        verifyNoInteractions(eventPublisher, auditService);
    }
}
