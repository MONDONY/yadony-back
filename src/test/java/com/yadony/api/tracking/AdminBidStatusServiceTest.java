package com.yadony.api.tracking;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.tracking.dto.AdminBidStatusChangeRequest;
import com.yadony.api.tracking.dto.AdminBidStatusChangeResponse;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminBidStatusServiceTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock TrackingEventRepository trackingEventRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    private AdminBidStatusService service;
    private final UUID bidId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private BidEntity bid;

    private static AdminBidStatusChangeRequest request(AdminForcedBidStatus status) {
        return new AdminBidStatusChangeRequest(status, "Message WhatsApp du destinataire du 24/11");
    }

    @BeforeEach
    void setUp() {
        service = new AdminBidStatusService(bidRepository, announcementRepository, trackingEventRepository,
                auditService, eventPublisher);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setSenderId(senderId);
        bid.setAnnouncementId(announcementId);
        bid.setStatus(BidStatus.HANDED_OVER);
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeAttempts(2);
        lenient().when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
    }

    private void withAnnouncement() {
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(travelerId);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
    }

    @Test
    void livre_consommeLeCode_traceArrivee_publieLEvenementEtAudite() {
        withAnnouncement();

        AdminBidStatusChangeResponse response = service.changeStatus(bidId, adminId, request(AdminForcedBidStatus.COMPLETED));

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.previousStatus()).isEqualTo("HANDED_OVER");
        assertThat(response.unchanged()).isFalse();
        assertThat(bid.getStatus()).isEqualTo(BidStatus.COMPLETED);
        assertThat(bid.getConfirmationCode()).isNull();
        assertThat(bid.getConfirmationCodeAttempts()).isZero();
        verify(bidRepository).lockForUpdate(bidId);
        verify(bidRepository).save(bid);

        ArgumentCaptor<TrackingEventEntity> event = ArgumentCaptor.forClass(TrackingEventEntity.class);
        verify(trackingEventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(TrackingEventType.ARRIVEE);
        assertThat(event.getValue().getBidId()).isEqualTo(bidId);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOf(DeliveryConfirmedEvent.class);

        verify(auditService).log(eq("BID"), eq(bidId), eq("ADMIN_BID_STATUS_FORCED"), eq(adminId),
                eq(Map.of("previousStatus", "HANDED_OVER", "newStatus", "COMPLETED",
                        "reason", "Message WhatsApp du destinataire du 24/11")));
    }

    @ParameterizedTest
    @EnumSource(value = AdminForcedBidStatus.class, names = {"HANDED_OVER", "IN_TRANSIT", "ARRIVED"})
    void statutLogistique_changeSeulementLeColis(AdminForcedBidStatus target) {
        bid.setStatus(BidStatus.ACCEPTED);
        withAnnouncement();

        AdminBidStatusChangeResponse response = service.changeStatus(bidId, adminId, request(target));

        assertThat(response.status()).isEqualTo(target.name());
        assertThat(bid.getStatus()).isEqualTo(target.bidStatus());
        assertThat(bid.getConfirmationCode()).isEqualTo("123456");
        verifyNoInteractions(trackingEventRepository, eventPublisher);
        verify(auditService).log(eq("BID"), eq(bidId), eq("ADMIN_BID_STATUS_FORCED"), eq(adminId), any());
    }

    @Test
    void dejaDansLeStatut_idempotent_rienNEstRefait() {
        bid.setStatus(BidStatus.COMPLETED);

        AdminBidStatusChangeResponse response = service.changeStatus(bidId, adminId, request(AdminForcedBidStatus.COMPLETED));

        assertThat(response.unchanged()).isTrue();
        verify(bidRepository, never()).save(any());
        verifyNoInteractions(trackingEventRepository, eventPublisher, auditService);
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"CANCELLED", "REJECTED", "EXPIRED", "NO_SHOW",
            "PARCEL_REFUSED", "PENDING", "PAYMENT_ESCROWED", "AWAITING_PAYMENT", "NEGOTIATING"})
    void statutNonModifiable_409(BidStatus current) {
        bid.setStatus(current);

        assertThatThrownBy(() -> service.changeStatus(bidId, adminId, request(AdminForcedBidStatus.COMPLETED)))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("bid-status-not-changeable");
                });
        assertThat(bid.getStatus()).isEqualTo(current);
        verify(bidRepository, never()).save(any());
        verifyNoInteractions(eventPublisher, auditService);
    }

    @Test
    void colisIntrouvable_404() {
        UUID other = UUID.randomUUID();
        when(bidRepository.findById(other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(other, adminId, request(AdminForcedBidStatus.COMPLETED)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void trajetIntrouvable_404_etRienNEstEcrit() {
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(bidId, adminId, request(AdminForcedBidStatus.COMPLETED)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(bid.getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        verify(bidRepository, never()).save(any());
    }
}
