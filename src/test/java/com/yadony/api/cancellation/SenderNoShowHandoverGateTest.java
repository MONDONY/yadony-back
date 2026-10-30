package com.yadony.api.cancellation;

import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SenderNoShowHandoverGateTest {

    private final CancellationRepository cancellations = mock(CancellationRepository.class);
    private final SenderNoShowHandoverGate gate = new SenderNoShowHandoverGate(cancellations);
    private final UUID bidId = UUID.randomUUID();

    private void declaration(String reason, CancellationStatus status) {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(bidId);
        c.setReason(reason);
        c.setNoShowStatus(status);
        when(cancellations.findByBidId(bidId)).thenReturn(Optional.of(c));
    }

    @ParameterizedTest
    @EnumSource(value = CancellationStatus.class, names = {"PENDING_CONFIRMATION", "CONTESTED", "CONFIRMED"})
    void declarationNonLevee_bloqueLaRemise(CancellationStatus status) {
        declaration(CancellationReason.SENDER_NO_SHOW.name(), status);

        assertThat(gate.blockingCode(bidId)).contains("handover-sender-noshow-pending");
    }

    @Test
    void declarationLevee_neBloquePas() {
        declaration(CancellationReason.SENDER_NO_SHOW.name(), CancellationStatus.RESOLVED);

        assertThat(gate.blockingCode(bidId)).isEmpty();
    }

    @Test
    void aucuneDeclaration_neBloquePas() {
        when(cancellations.findByBidId(bidId)).thenReturn(Optional.empty());

        assertThat(gate.blockingCode(bidId)).isEmpty();
    }

    @Test
    void autreMotifHandover_neBloquePas() {
        declaration(CancellationReason.TRAVELER_CANCEL_AFTER_HANDOVER.name(), CancellationStatus.CONFIRMED);

        assertThat(gate.blockingCode(bidId)).isEmpty();
    }

    @Test
    void messageTraduitParSaCle_etPorteConsulteeEnPremier() {
        assertThat(gate.messageKey()).isEqualTo("problem.handover-sender-noshow-pending");
        assertThat(SenderNoShowHandoverGate.class.getAnnotation(Order.class).value())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void statutsOuLAbsenceNASPlusDeSens() {
        assertThat(SenderNoShowHandoverGate.parcelAlreadyHandedOver(BidStatus.ACCEPTED)).isFalse();
        assertThat(SenderNoShowHandoverGate.parcelAlreadyHandedOver(BidStatus.CANCELLED)).isFalse();
        assertThat(SenderNoShowHandoverGate.parcelAlreadyHandedOver(null)).isFalse();
        for (BidStatus s : new BidStatus[]{BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED,
                BidStatus.COMPLETED, BidStatus.PARCEL_REFUSED}) {
            assertThat(SenderNoShowHandoverGate.parcelAlreadyHandedOver(s)).as(s.name()).isTrue();
        }
    }
}
