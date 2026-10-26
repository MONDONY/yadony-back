package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminIncidentContextResponse;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminIncidentContextControllerTest {

    @Mock DisputeRepository disputeRepo;
    @Mock CancellationRepository cancellationRepo;
    @Mock IncidentContextService contextService;

    private AdminIncidentContextController controller() {
        return new AdminIncidentContextController(disputeRepo, cancellationRepo, contextService);
    }

    @Test
    void disputeContext_returnsTheContextOfTheDispute() {
        UUID id = UUID.randomUUID();
        DisputeEntity dispute = new DisputeEntity();
        AdminIncidentContextResponse ctx = new AdminIncidentContextResponse(List.of(), List.of());
        when(disputeRepo.findById(id)).thenReturn(Optional.of(dispute));
        when(contextService.forDispute(dispute)).thenReturn(ctx);

        var resp = controller().disputeContext(id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isSameAs(ctx);
    }

    @Test
    void disputeContext_unknownDispute_is404() {
        UUID id = UUID.randomUUID();
        when(disputeRepo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller().disputeContext(id))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(contextService);
    }

    @Test
    void noShowContext_returnsTheContextOfTheDeclaration() {
        UUID id = UUID.randomUUID();
        CancellationEntity c = new CancellationEntity();
        c.setReason("SENDER_NO_SHOW");
        AdminIncidentContextResponse ctx = new AdminIncidentContextResponse(List.of(), List.of());
        when(cancellationRepo.findById(id)).thenReturn(Optional.of(c));
        when(contextService.forCancellation(c)).thenReturn(ctx);

        assertThat(controller().noShowContext(id).getBody()).isSameAs(ctx);
    }

    @Test
    void noShowContext_aCancellationThatIsNotANoShow_is404() {
        UUID id = UUID.randomUUID();
        CancellationEntity c = new CancellationEntity();
        c.setReason("TRAVELER_CANCELLED");
        when(cancellationRepo.findById(id)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> controller().noShowContext(id))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("noshow-not-found"));
    }

    @Test
    void noShowContext_unknownId_is404() {
        UUID id = UUID.randomUUID();
        when(cancellationRepo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller().noShowContext(id)).isInstanceOf(YadonyBusinessException.class);
    }
}
