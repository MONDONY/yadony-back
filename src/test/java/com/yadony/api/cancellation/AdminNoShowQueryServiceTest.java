package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.dto.AdminNoShowResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminNoShowQueryServiceTest {

    @Mock CancellationRepository cancellationRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock DisputeRepository disputeRepository;

    @InjectMocks AdminNoShowQueryService service;

    final UUID senderId = UUID.randomUUID();
    final UUID travelerId = UUID.randomUUID();
    final UUID annId = UUID.randomUUID();

    private UserEntity user(UUID id, String first, String last) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    private BidEntity bid(UUID id, PaymentMethod method) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", id);
        b.setSenderId(senderId);
        b.setAnnouncementId(annId);
        b.setStatus(BidStatus.ACCEPTED);
        b.setPaymentMethod(method);
        b.setCurrency("xof");
        b.setRecipientName("Awa Diop Ndiaye");
        b.setHandoverDeadline(LocalDateTime.of(2026, 9, 27, 18, 0));
        return b;
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", annId);
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.of(2026, 9, 28));
        return a;
    }

    private CancellationEntity row(UUID bidId, CancellationScope scope, String reason, UUID declarant,
                                   CancellationStatus status) {
        CancellationEntity c = new CancellationEntity();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setBidId(bidId);
        c.setScope(scope);
        c.setReason(reason);
        c.setCancelledBy(declarant);
        c.setNoShowStatus(status);
        c.setContestationDeadline(OffsetDateTime.now().plusMinutes(90));
        return c;
    }

    private void stubPage(List<CancellationEntity> rows) {
        when(cancellationRepository.findAdminNoShows(anyCollection(), anyCollection(), anyCollection(),
                any(Pageable.class))).thenReturn(new PageImpl<>(rows));
    }

    @Test
    @SuppressWarnings("unchecked")
    void filtresParDefaut_enAttente_toutesPortees_seulementLesNoShows() {
        stubPage(List.of());

        service.list(null, null, 0, 20, true);

        ArgumentCaptor<Collection<String>> reasons = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<CancellationScope>> scopes = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<CancellationStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(cancellationRepository).findAdminNoShows(reasons.capture(), scopes.capture(), statuses.capture(),
                any(Pageable.class));
        assertThat(reasons.getValue())
                .containsExactlyInAnyOrder("SENDER_NO_SHOW", "RECIPIENT_NO_SHOW", "TRAVELER_DELIVERY_NO_SHOW");
        assertThat(scopes.getValue()).containsExactlyInAnyOrder(CancellationScope.values());
        assertThat(statuses.getValue()).containsExactly(CancellationStatus.PENDING_CONFIRMATION);
        verifyNoInteractions(bidRepository, announcementRepository, userRepository, paymentRepository,
                disputeRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void filtresExplicites_statutAll_porteeDelivery() {
        stubPage(List.of());

        service.list("all", "DELIVERY", 0, 20, true);

        ArgumentCaptor<Collection<CancellationScope>> scopes = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<CancellationStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(cancellationRepository).findAdminNoShows(anyCollection(), scopes.capture(), statuses.capture(),
                any(Pageable.class));
        assertThat(scopes.getValue()).containsExactly(CancellationScope.DELIVERY);
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(CancellationStatus.values());
    }

    @Test
    @SuppressWarnings("unchecked")
    void filtreStatutUnique_contested() {
        stubPage(List.of());

        service.list("CONTESTED", "ALL", 0, 20, true);

        ArgumentCaptor<Collection<CancellationStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(cancellationRepository).findAdminNoShows(anyCollection(), anyCollection(), statuses.capture(),
                any(Pageable.class));
        assertThat(statuses.getValue()).containsExactly(CancellationStatus.CONTESTED);
    }

    @Test
    void filtreInvalide_422() {
        assertThatThrownBy(() -> service.list("PENDING", null, 0, 20, true))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("invalid-noshow-filter");
                });
        assertThatThrownBy(() -> service.list(null, "ARRIVAL", 0, 20, true))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void ligneLisible_declarantAccuseTrajetPaiementLitige_requetesGroupees() {
        UUID bidHandover = UUID.randomUUID();
        UUID bidDelivery = UUID.randomUUID();
        UUID bidTravelerAbsent = UUID.randomUUID();
        CancellationEntity handover = row(bidHandover, CancellationScope.HANDOVER, "SENDER_NO_SHOW",
                travelerId, CancellationStatus.PENDING_CONFIRMATION);
        CancellationEntity recipient = row(bidDelivery, CancellationScope.DELIVERY, "RECIPIENT_NO_SHOW",
                travelerId, CancellationStatus.CONTESTED);
        CancellationEntity travelerAbsent = row(bidTravelerAbsent, CancellationScope.DELIVERY,
                "TRAVELER_DELIVERY_NO_SHOW", senderId, CancellationStatus.RESOLVED);
        travelerAbsent.setAdminDecision(NoShowAdminDecision.REJECTED);
        travelerAbsent.setDecisionReason("Le destinataire confirme la réception");
        stubPage(List.of(handover, recipient, travelerAbsent));

        BidEntity b1 = bid(bidHandover, PaymentMethod.STRIPE);
        BidEntity b2 = bid(bidDelivery, PaymentMethod.CASH);
        b2.setCommissionStatus(CommissionStatus.CHARGED);
        BidEntity b3 = bid(bidTravelerAbsent, PaymentMethod.STRIPE);
        when(bidRepository.findAllById(anyCollection())).thenReturn(List.of(b1, b2, b3));
        when(announcementRepository.findAllById(anyCollection())).thenReturn(List.of(announcement()));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(
                user(senderId, "Moussa", "Traoré"), user(travelerId, "Fatou", "Sow")));
        PaymentEntity p1 = new PaymentEntity();
        p1.setBidId(bidHandover);
        p1.setAmount(new BigDecimal("42.50"));
        p1.setCurrency("EUR");
        p1.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.findByBidIdIn(anyCollection())).thenReturn(List.of(p1));
        DisputeEntity resolved = new DisputeEntity();
        ReflectionTestUtils.setField(resolved, "id", UUID.randomUUID());
        resolved.setBidId(bidDelivery);
        resolved.setType("RECIPIENT_NO_SHOW");
        resolved.setStatus("RESOLVED");
        DisputeEntity open = new DisputeEntity();
        UUID openId = UUID.randomUUID();
        ReflectionTestUtils.setField(open, "id", openId);
        open.setBidId(bidDelivery);
        open.setType("RECIPIENT_NO_SHOW_CONTESTED");
        open.setStatus("OPEN");
        DisputeEntity unrelated = new DisputeEntity();
        ReflectionTestUtils.setField(unrelated, "id", UUID.randomUUID());
        unrelated.setBidId(bidHandover);
        unrelated.setType("RECIPIENT_NO_SHOW");
        when(disputeRepository.findByBidIdIn(anyCollection())).thenReturn(List.of(resolved, open, unrelated));

        Page<AdminNoShowResponse> page = service.list("ALL", "ALL", 0, 20, true);

        // Pas de N+1 : une requête par table pour toute la page.
        verify(bidRepository, times(1)).findAllById(anyCollection());
        verify(announcementRepository, times(1)).findAllById(anyCollection());
        verify(userRepository, times(1)).findAllById(anyCollection());
        verify(paymentRepository, times(1)).findByBidIdIn(anyCollection());
        verify(disputeRepository, times(1)).findByBidIdIn(anyCollection());

        AdminNoShowResponse h = page.getContent().get(0);
        assertThat(h.id()).isEqualTo(handover.getId());
        assertThat(h.bidId()).isEqualTo(bidHandover);
        assertThat(h.scope()).isEqualTo("HANDOVER");
        assertThat(h.reason()).isEqualTo("SENDER_NO_SHOW");
        assertThat(h.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(h.noShowStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(h.cancelledBy()).isEqualTo(travelerId);
        assertThat(h.remainingMinutes()).isBetween(88L, 90L);
        assertThat(h.declarant()).isEqualTo(new AdminNoShowResponse.Party(travelerId, "Fatou S.", "TRAVELER"));
        assertThat(h.accused()).isEqualTo(new AdminNoShowResponse.Party(senderId, "Moussa T.", "SENDER"));
        assertThat(h.trip()).isEqualTo(new AdminNoShowResponse.Trip("Paris", "Dakar", LocalDate.of(2026, 9, 28)));
        assertThat(h.handoverAt()).isEqualTo(LocalDateTime.of(2026, 9, 27, 18, 0));
        assertThat(h.amount()).isEqualByComparingTo("42.50");
        assertThat(h.currency()).isEqualTo("EUR");
        assertThat(h.paymentMethod()).isEqualTo("STRIPE");
        assertThat(h.paymentStatus()).isEqualTo("ESCROW");
        assertThat(h.bidStatus()).isEqualTo("ACCEPTED");
        assertThat(h.dispute()).isNull();
        assertThat(h.canConfirm()).isTrue();
        assertThat(h.canReject()).isTrue();
        assertThat(h.adminDecision()).isNull();

        AdminNoShowResponse r = page.getContent().get(1);
        assertThat(r.declarant().role()).isEqualTo("TRAVELER");
        assertThat(r.accused()).isEqualTo(new AdminNoShowResponse.Party(null, "Awa N.", "RECIPIENT"));
        assertThat(r.remainingMinutes()).isNull();
        assertThat(r.paymentMethod()).isEqualTo("CASH");
        assertThat(r.paymentStatus()).isEqualTo("CHARGED");
        assertThat(r.commissionStatus()).isEqualTo("CHARGED");
        assertThat(r.currency()).isEqualTo("XOF");
        assertThat(r.amount()).isNull();
        // Le litige OUVERT prime sur un litige résolu du même bid.
        assertThat(r.dispute()).isEqualTo(new AdminNoShowResponse.DisputeRef(openId, "OPEN"));
        assertThat(r.canConfirm()).isTrue();

        AdminNoShowResponse t = page.getContent().get(2);
        assertThat(t.declarant()).isEqualTo(new AdminNoShowResponse.Party(senderId, "Moussa T.", "SENDER"));
        assertThat(t.accused()).isEqualTo(new AdminNoShowResponse.Party(travelerId, "Fatou S.", "TRAVELER"));
        assertThat(t.canConfirm()).isFalse();
        assertThat(t.canReject()).isFalse();
        assertThat(t.adminDecision()).isEqualTo("REJECTED");
        assertThat(t.decisionReason()).isEqualTo("Le destinataire confirme la réception");
    }

    @Test
    void appelantSansDisputeResolve_neVoitAucuneAction() {
        UUID bidId = UUID.randomUUID();
        stubPage(List.of(row(bidId, CancellationScope.HANDOVER, "SENDER_NO_SHOW", travelerId,
                CancellationStatus.PENDING_CONFIRMATION)));

        AdminNoShowResponse only = service.list(null, null, 0, 20, false).getContent().get(0);

        assertThat(only.canConfirm()).isFalse();
        assertThat(only.canReject()).isFalse();
        // Bid, annonce, comptes absents : la ligne reste servie, champs vides.
        assertThat(only.trip()).isNull();
        assertThat(only.declarant()).isEqualTo(new AdminNoShowResponse.Party(travelerId, null, "TRAVELER"));
        assertThat(only.accused()).isEqualTo(new AdminNoShowResponse.Party(null, null, "SENDER"));
    }

    @Test
    void delaiDepasse_resteAZeroMinute() {
        CancellationEntity c = row(UUID.randomUUID(), CancellationScope.HANDOVER, "SENDER_NO_SHOW", travelerId,
                CancellationStatus.PENDING_CONFIRMATION);
        c.setContestationDeadline(OffsetDateTime.now().minusHours(2));

        assertThat(service.describe(c, true).remainingMinutes()).isZero();
    }

    @Test
    void nomsCourts() {
        assertThat(AdminNoShowQueryService.shortName(null)).isNull();
        assertThat(AdminNoShowQueryService.shortName("   ")).isNull();
        assertThat(AdminNoShowQueryService.shortName("Awa")).isEqualTo("Awa");
        assertThat(AdminNoShowQueryService.shortName(" awa  diop ")).isEqualTo("awa D.");
    }
}
