package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.SenderNoShowWithdrawnEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.tracking.HandoverScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SenderNoShowHandoverGateTest {

    private final CancellationRepository cancellations = mock(CancellationRepository.class);
    private final BidRepository bids = mock(BidRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SenderNoShowHandoverGate gate = new SenderNoShowHandoverGate(cancellations, bids, audit, events);
    private final UUID bidId = UUID.randomUUID();
    private final UUID cancellationId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID trackingEventId = UUID.randomUUID();

    private CancellationEntity declaration(String reason, CancellationStatus status) {
        CancellationEntity c = new CancellationEntity();
        ReflectionTestUtils.setField(c, "id", cancellationId);
        c.setBidId(bidId);
        c.setReason(reason);
        c.setNoShowStatus(status);
        when(cancellations.findByBidId(bidId)).thenReturn(Optional.of(c));
        return c;
    }

    private HandoverScan scan() {
        return new HandoverScan(bidId, travelerId, trackingEventId, "QR", "tracking/" + bidId + "/1_depart.jpg",
                LocalDateTime.of(2026, 10, 30, 9, 15), new BigDecimal("48.856600"), new BigDecimal("2.352200"));
    }

    @ParameterizedTest
    @EnumSource(value = CancellationStatus.class, names = {"CONTESTED", "CONFIRMED"})
    void declarationContesteeOuConfirmee_bloqueLaRemise_sansRienModifier(CancellationStatus status) {
        CancellationEntity c = declaration(CancellationReason.SENDER_NO_SHOW.name(), status);

        assertThat(gate.blockingCode(bidId)).contains("handover-sender-noshow-pending");
        gate.onHandover(scan());

        assertThat(c.getNoShowStatus()).isEqualTo(status);
        verify(cancellations, never()).save(any());
        verifyNoInteractions(audit, events);
    }

    @Test
    void declarationEnAttente_neBloquePas_etLaDecisionNEcritRien() {
        // « La remise est-elle bloquée ? » est une lecture : la levée n'a lieu qu'une fois la remise enregistrée.
        CancellationEntity c = declaration(CancellationReason.SENDER_NO_SHOW.name(),
                CancellationStatus.PENDING_CONFIRMATION);

        assertThat(gate.blockingCode(bidId)).isEmpty();

        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.PENDING_CONFIRMATION);
        verify(cancellations, never()).save(any());
        verifyNoInteractions(audit, events, bids);
    }

    @Test
    void declarationEnAttente_leveeApresLaRemise_auditAvecLaPreuveDuScan() {
        CancellationEntity c = declaration(CancellationReason.SENDER_NO_SHOW.name(),
                CancellationStatus.PENDING_CONFIRMATION);
        BidEntity bid = new BidEntity();
        bid.setSenderId(senderId);
        when(bids.findById(bidId)).thenReturn(Optional.of(bid));

        gate.onHandover(scan());

        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.RESOLVED);
        verify(cancellations).save(c);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("bidId", bidId.toString());
        expected.put("trackingEventId", trackingEventId.toString());
        expected.put("scanMethod", "QR");
        expected.put("photoKey", "tracking/" + bidId + "/1_depart.jpg");
        expected.put("offlineTimestamp", "2026-10-30T09:15");
        expected.put("gpsLat", "48.856600");
        expected.put("gpsLon", "2.352200");
        expected.put("previousStatus", "PENDING_CONFIRMATION");
        verify(audit).log("CANCELLATION", cancellationId, "SENDER_NO_SHOW_WITHDRAWN_BY_HANDOVER", travelerId, expected);
        verify(events).publishEvent(new SenderNoShowWithdrawnEvent(bidId, cancellationId, senderId));
    }

    @Test
    void scanSansPhotoNiPositionEnLigne_auditSansCesChamps() {
        CancellationEntity c = declaration(CancellationReason.SENDER_NO_SHOW.name(),
                CancellationStatus.PENDING_CONFIRMATION);
        when(bids.findById(bidId)).thenReturn(Optional.empty());

        gate.onHandover(new HandoverScan(bidId, travelerId, trackingEventId, null, null, null, null, null));

        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.RESOLVED);
        verify(audit).log("CANCELLATION", cancellationId, "SENDER_NO_SHOW_WITHDRAWN_BY_HANDOVER", travelerId,
                Map.of("bidId", bidId.toString(), "trackingEventId", trackingEventId.toString(),
                        "previousStatus", "PENDING_CONFIRMATION"));
        // Colis introuvable : pas d'expéditeur à prévenir.
        verifyNoInteractions(events);
    }

    @Test
    void declarationLevee_niBloqueNiModifiee() {
        declaration(CancellationReason.SENDER_NO_SHOW.name(), CancellationStatus.RESOLVED);

        assertThat(gate.blockingCode(bidId)).isEmpty();
        gate.onHandover(scan());
        verify(cancellations, never()).save(any());
        verifyNoInteractions(audit, events);
    }

    @Test
    void aucuneDeclaration_rienNeSePasse() {
        when(cancellations.findByBidId(bidId)).thenReturn(Optional.empty());

        assertThat(gate.blockingCode(bidId)).isEmpty();
        gate.onHandover(scan());
        verifyNoInteractions(audit, events);
    }

    @Test
    void autreMotifHandover_niBloqueNiLeve() {
        CancellationEntity c = declaration(CancellationReason.TRAVELER_CANCEL_AFTER_HANDOVER.name(),
                CancellationStatus.PENDING_CONFIRMATION);

        assertThat(gate.blockingCode(bidId)).isEmpty();
        gate.onHandover(scan());
        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.PENDING_CONFIRMATION);
        verifyNoInteractions(audit, events);
    }

    @Test
    void messageTraduitParSaCle_etPorteConsulteeEnPremier() {
        assertThat(gate.messageKey()).isEqualTo("problem.handover-sender-noshow-pending");
        assertThat(SenderNoShowHandoverGate.class.getAnnotation(Order.class).value())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
