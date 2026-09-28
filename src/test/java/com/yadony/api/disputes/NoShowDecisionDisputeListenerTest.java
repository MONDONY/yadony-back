package com.yadony.api.disputes;

import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.cancellation.NoShowAdminDecision;
import com.yadony.api.cancellation.events.NoShowAdminDecisionEvent;
import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NoShowDecisionDisputeListenerTest {

    @Mock DisputeRepository disputeRepository;
    @Mock AuditService auditService;

    @InjectMocks NoShowDecisionDisputeListener listener;

    final UUID bidId = UUID.randomUUID();
    final UUID cancellationId = UUID.randomUUID();
    final UUID adminId = UUID.randomUUID();

    private NoShowAdminDecisionEvent event(NoShowAdminDecision decision, List<String> types) {
        return new NoShowAdminDecisionEvent(cancellationId, bidId, CancellationScope.DELIVERY, "RECIPIENT_NO_SHOW",
                decision, UUID.randomUUID(), UUID.randomUUID(), adminId, types);
    }

    private DisputeEntity dispute(String status) {
        DisputeEntity d = new DisputeEntity();
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        d.setBidId(bidId);
        d.setStatus(status);
        return d;
    }

    @Test
    void rejet_fermeLeLitigeOuvertCommeClasse_sansNoteVisible() {
        DisputeEntity open = dispute("OPEN");
        when(disputeRepository.findByBidIdAndType(bidId, "RECIPIENT_NO_SHOW_CONTESTED")).thenReturn(Optional.of(open));
        when(disputeRepository.findByBidIdAndType(bidId, "RECIPIENT_NO_SHOW")).thenReturn(Optional.empty());

        listener.onDecision(event(NoShowAdminDecision.REJECTED,
                List.of("RECIPIENT_NO_SHOW_CONTESTED", "RECIPIENT_NO_SHOW")));

        assertThat(open.getStatus()).isEqualTo("RESOLVED");
        assertThat(open.getResolutionType()).isEqualTo("NOSHOW_REJECTED");
        assertThat(open.getResolvedAt()).isNotNull();
        // Le motif admin est interne : jamais recopié dans la note que voient les parties.
        assertThat(open.getResolutionNote()).isNull();
        verify(disputeRepository).save(open);
        verify(auditService).log("DISPUTE", open.getId(), "RESOLVED_BY_NOSHOW_DECISION", adminId,
                Map.of("cancellationId", cancellationId.toString(), "decision", "REJECTED"));
    }

    @Test
    void confirmation_fermeCommeConfirmee() {
        DisputeEntity open = dispute("OPEN");
        when(disputeRepository.findByBidIdAndType(bidId, "SENDER_NO_SHOW_CONTESTED")).thenReturn(Optional.of(open));

        listener.onDecision(event(NoShowAdminDecision.CONFIRMED, List.of("SENDER_NO_SHOW_CONTESTED")));

        assertThat(open.getResolutionType()).isEqualTo("NOSHOW_CONFIRMED");
    }

    @Test
    void litigeDejaResolu_intact() {
        DisputeEntity resolved = dispute("RESOLVED");
        resolved.setResolutionType("REFUND_SENDER");
        when(disputeRepository.findByBidIdAndType(bidId, "SENDER_NO_SHOW_CONTESTED"))
                .thenReturn(Optional.of(resolved));

        listener.onDecision(event(NoShowAdminDecision.CONFIRMED, List.of("SENDER_NO_SHOW_CONTESTED")));

        assertThat(resolved.getResolutionType()).isEqualTo("REFUND_SENDER");
        verify(disputeRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void aucunTypeAFermer_rienNeBouge() {
        listener.onDecision(event(NoShowAdminDecision.CONFIRMED, List.of()));

        verifyNoInteractions(disputeRepository, auditService);
    }
}
