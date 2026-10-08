package com.yadony.api.cancellation;

import com.yadony.api.common.AuditService;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryNoShowHoldClosingListenerTest {

    @Mock CancellationRepository cancellationRepository;
    @Mock AuditService auditService;
    @InjectMocks DeliveryNoShowHoldClosingListener listener;

    UUID bidId = UUID.randomUUID();

    private CancellationEntity report(String reason, CancellationStatus status, OffsetDateTime hold) {
        CancellationEntity c = new CancellationEntity();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setBidId(bidId);
        c.setScope(CancellationScope.DELIVERY);
        c.setReason(reason);
        c.setNoShowStatus(status);
        c.setHoldUntil(hold);
        return c;
    }

    private void deliver() {
        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void livraisonPendantLaGarde_clotLeSignalement() {
        CancellationEntity c = report("RECIPIENT_NO_SHOW", CancellationStatus.CONFIRMED, OffsetDateTime.now().plusDays(2));
        when(cancellationRepository.findByBidIdAndScope(bidId, CancellationScope.DELIVERY)).thenReturn(Optional.of(c));

        deliver();

        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.RESOLVED);
        verify(cancellationRepository).save(c);
        verify(auditService).log(eq("CANCELLATION"), eq(c.getId()), eq("DELIVERY_NOSHOW_HOLD_CLOSED_BY_DELIVERY"), isNull(), any());
    }

    @Test
    void signalementConteste_ouSansGarde_ouAutreMotif_intouche() {
        for (CancellationEntity c : new CancellationEntity[]{
                report("RECIPIENT_NO_SHOW", CancellationStatus.CONTESTED, OffsetDateTime.now()),
                report("RECIPIENT_NO_SHOW", CancellationStatus.CONFIRMED, null),
                report("TRAVELER_DELIVERY_NO_SHOW", CancellationStatus.PENDING_CONFIRMATION, OffsetDateTime.now())}) {
            when(cancellationRepository.findByBidIdAndScope(bidId, CancellationScope.DELIVERY)).thenReturn(Optional.of(c));
            deliver();
        }
        verify(cancellationRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }
}
