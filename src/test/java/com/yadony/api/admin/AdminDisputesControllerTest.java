package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminCancellationResponse;
import com.yadony.api.admin.dto.AdminDisputeDetailResponse;
import com.yadony.api.admin.dto.AdminDisputeListItemResponse;
import com.yadony.api.admin.dto.AdminGuaranteeFundRequest;
import com.yadony.api.admin.dto.AdminResolveDisputeRequest;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.events.DisputeResolvedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminDisputesControllerTest {

    @Mock DisputeRepository disputeRepo;
    @Mock CancellationRepository cancellationRepo;
    @Mock AuditService auditService;
    @Mock UserRepository userRepo;
    @Mock ApplicationEventPublisher eventPublisher;

    private AdminDisputesController controller() {
        return new AdminDisputesController(
                disputeRepo, cancellationRepo, auditService, userRepo, eventPublisher);
    }

    // ---- listDisputes ----

    @Test
    void listDisputes_noFilter_returnsPage() {
        Page<DisputeEntity> page = new PageImpl<>(List.of());
        when(disputeRepo.findAdminFiltered(isNull(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminDisputeListItemResponse>> resp =
                controller().listDisputes(null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTotalElements()).isEqualTo(0);
    }

    @Test
    void listDisputes_withStatusFilter_passesStatus() {
        Page<DisputeEntity> page = new PageImpl<>(List.of());
        when(disputeRepo.findAdminFiltered(eq("OPEN"), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminDisputeListItemResponse>> resp =
                controller().listDisputes("OPEN", 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(disputeRepo).findAdminFiltered(eq("OPEN"), any(Pageable.class));
    }

    // ---- getDispute ----

    @Test
    void getDispute_found_returnsDetail() {
        UUID id = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("OPEN");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));

        ResponseEntity<AdminDisputeDetailResponse> resp = controller().getDispute(id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo("OPEN");
    }

    @Test
    void getDispute_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(disputeRepo.findById(id)).thenReturn(Optional.empty());

        assertThrows(YadonyBusinessException.class, () -> controller().getDispute(id));
    }

    // ---- resolveDispute ----

    @Test
    void resolveDispute_setsFieldsAndReturns200() {
        UUID id = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("OPEN");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));
        when(disputeRepo.save(entity)).thenReturn(entity);

        AdminResolveDisputeRequest request = new AdminResolveDisputeRequest("REFUND_SENDER", "note");
        ResponseEntity<AdminDisputeDetailResponse> resp = controller().resolveDispute(id, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getStatus()).isEqualTo("RESOLVED");
        assertThat(entity.getResolutionType()).isEqualTo("REFUND_SENDER");
        assertThat(entity.getResolutionNote()).isEqualTo("note");
        assertThat(entity.getResolvedAt()).isNotNull();
        verify(disputeRepo).save(entity);
        verify(auditService).log(eq("DISPUTE"), eq(entity.getId()), eq("RESOLVE"), isNull(), any());
        verify(eventPublisher).publishEvent(ArgumentMatchers.<Object>argThat(event ->
                event instanceof DisputeResolvedEvent resolved
                        && resolved.disputeId().equals(id)
                        && "REFUND_SENDER".equals(resolved.resolution())));
    }

    @Test
    void resolveDispute_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(disputeRepo.findById(id)).thenReturn(Optional.empty());

        assertThrows(YadonyBusinessException.class,
                () -> controller().resolveDispute(id, new AdminResolveDisputeRequest("res", "note")));
    }

    @Test
    void resolveDispute_alreadyResolved_throws409WithoutPublishingAgain() {
        UUID id = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("RESOLVED");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));

        YadonyBusinessException error = assertThrows(YadonyBusinessException.class,
                () -> controller().resolveDispute(
                        id, new AdminResolveDisputeRequest("REFUND_SENDER", "retry")));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verify(disputeRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void resolveDispute_senderNoShowContested_transitionsLinkedHandoverCancellationToResolved() {
        UUID id = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("OPEN");
        entity.setBidId(bidId);
        entity.setType("SENDER_NO_SHOW_CONTESTED");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));
        when(disputeRepo.save(entity)).thenReturn(entity);

        CancellationEntity cancellation = new CancellationEntity();
        cancellation.setNoShowStatus(CancellationStatus.CONTESTED);
        when(cancellationRepo.findByBidId(bidId)).thenReturn(Optional.of(cancellation));

        controller().resolveDispute(id, new AdminResolveDisputeRequest("REFUND_SENDER", "note"));

        assertThat(cancellation.getNoShowStatus()).isEqualTo(CancellationStatus.RESOLVED);
        verify(cancellationRepo).save(cancellation);
        verify(cancellationRepo, never()).findByBidIdAndScope(any(), any());
    }

    @Test
    void resolveDispute_recipientNoShowContested_transitionsLinkedDeliveryCancellationToResolved() {
        UUID id = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("OPEN");
        entity.setBidId(bidId);
        entity.setType("RECIPIENT_NO_SHOW_CONTESTED");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));
        when(disputeRepo.save(entity)).thenReturn(entity);

        CancellationEntity cancellation = new CancellationEntity();
        cancellation.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        when(cancellationRepo.findByBidIdAndScope(bidId, com.yadony.api.cancellation.CancellationScope.DELIVERY))
                .thenReturn(Optional.of(cancellation));

        controller().resolveDispute(id, new AdminResolveDisputeRequest("REFUND_SENDER", "note"));

        assertThat(cancellation.getNoShowStatus()).isEqualTo(CancellationStatus.RESOLVED);
        verify(cancellationRepo).save(cancellation);
        verify(cancellationRepo, never()).findByBidId(any());
    }

    @Test
    void resolveDispute_noLinkedCancellation_doesNotThrowOrSave() {
        UUID id = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("OPEN");
        entity.setBidId(bidId);
        entity.setType("SENDER_NO_SHOW_CONTESTED");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));
        when(disputeRepo.save(entity)).thenReturn(entity);
        when(cancellationRepo.findByBidId(bidId)).thenReturn(Optional.empty());

        controller().resolveDispute(id, new AdminResolveDisputeRequest("REFUND_SENDER", "note"));

        verify(cancellationRepo, never()).save(any());
    }

    @Test
    void resolveDispute_cancellationAlreadyConfirmed_leftUntouched() {
        UUID id = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("OPEN");
        entity.setBidId(bidId);
        entity.setType("SENDER_NO_SHOW_CONTESTED");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));
        when(disputeRepo.save(entity)).thenReturn(entity);

        CancellationEntity cancellation = new CancellationEntity();
        cancellation.setNoShowStatus(CancellationStatus.CONFIRMED);
        when(cancellationRepo.findByBidId(bidId)).thenReturn(Optional.of(cancellation));

        controller().resolveDispute(id, new AdminResolveDisputeRequest("REFUND_SENDER", "note"));

        assertThat(cancellation.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
        verify(cancellationRepo, never()).save(any());
    }

    // ---- payGuaranteeFund ----

    @Test
    void payGuaranteeFund_setsFieldsAndReturns200() {
        UUID id = UUID.randomUUID();
        UUID beneficiary = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("OPEN");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));
        when(disputeRepo.save(entity)).thenReturn(entity);

        AdminGuaranteeFundRequest request = new AdminGuaranteeFundRequest(5000, beneficiary, "paiement fonds de garantie");
        ResponseEntity<AdminDisputeDetailResponse> resp = controller().payGuaranteeFund(id, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getStatus()).isEqualTo("RESOLVED");
        assertThat(entity.getResolutionType()).isEqualTo("GUARANTEE_PAID");
        assertThat(entity.getBeneficiaryUserId()).isEqualTo(beneficiary);
        assertThat(entity.getResolvedAt()).isNotNull();
        verify(disputeRepo).save(entity);
        verify(auditService).log(eq("DISPUTE"), eq(entity.getId()), eq("GUARANTEE_FUND"), isNull(), any());
        verify(eventPublisher).publishEvent(ArgumentMatchers.<Object>argThat(event ->
                event instanceof DisputeResolvedEvent resolved
                        && resolved.disputeId().equals(id)
                        && "GUARANTEE_PAID".equals(resolved.resolution())));
    }

    @Test
    void payGuaranteeFund_transitionsLinkedCancellationToResolved() {
        UUID id = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UUID beneficiary = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("OPEN");
        entity.setBidId(bidId);
        entity.setType("TRAVELER_DELIVERY_NO_SHOW_CONTESTED");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));
        when(disputeRepo.save(entity)).thenReturn(entity);

        CancellationEntity cancellation = new CancellationEntity();
        cancellation.setNoShowStatus(CancellationStatus.CONTESTED);
        when(cancellationRepo.findByBidIdAndScope(bidId, com.yadony.api.cancellation.CancellationScope.DELIVERY))
                .thenReturn(Optional.of(cancellation));

        controller().payGuaranteeFund(id,
                new AdminGuaranteeFundRequest(5000, beneficiary, "paiement fonds de garantie"));

        assertThat(cancellation.getNoShowStatus()).isEqualTo(CancellationStatus.RESOLVED);
        verify(cancellationRepo).save(cancellation);
    }

    @Test
    void payGuaranteeFund_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(disputeRepo.findById(id)).thenReturn(Optional.empty());

        assertThrows(YadonyBusinessException.class,
                () -> controller().payGuaranteeFund(id,
                        new AdminGuaranteeFundRequest(5000, UUID.randomUUID(), "reason")));
    }

    @Test
    void payGuaranteeFund_alreadyResolved_throws409WithoutPublishingAgain() {
        UUID id = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("RESOLVED");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));

        YadonyBusinessException error = assertThrows(YadonyBusinessException.class,
                () -> controller().payGuaranteeFund(id,
                        new AdminGuaranteeFundRequest(5000, UUID.randomUUID(), "retry")));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verify(disputeRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    // ---- listCancellations ----

    @Test
    void listCancellations_noFilter_returnsPage() {
        Page<CancellationEntity> page = new PageImpl<>(List.of());
        when(cancellationRepo.findAdminFiltered(isNull(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminCancellationResponse>> resp =
                controller().listCancellations(null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void listCancellations_withStatusFilter_passesEnum() {
        Page<CancellationEntity> page = new PageImpl<>(List.of());
        when(cancellationRepo.findAdminFiltered(eq(CancellationStatus.CONTESTED), any(Pageable.class)))
                .thenReturn(page);

        ResponseEntity<Page<AdminCancellationResponse>> resp =
                controller().listCancellations(CancellationStatus.CONTESTED, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cancellationRepo).findAdminFiltered(eq(CancellationStatus.CONTESTED), any(Pageable.class));
    }

    @Test
    void listCancellations_withNullStatus_returnsOk() {
        Page<CancellationEntity> page = new PageImpl<>(List.of());
        when(cancellationRepo.findAdminFiltered(eq(null), any(Pageable.class)))
                .thenReturn(page);

        ResponseEntity<Page<AdminCancellationResponse>> resp =
                controller().listCancellations(null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cancellationRepo).findAdminFiltered(eq(null), any(Pageable.class));
    }
}
