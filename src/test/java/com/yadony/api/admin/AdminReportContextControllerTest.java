package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminReportContextResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminReportContextControllerTest {

    @Mock ReportRepository reportRepo;
    @Mock ReportContextService contextService;

    private AdminReportContextController controller() {
        return new AdminReportContextController(reportRepo, contextService);
    }

    private static UsernamePasswordAuthenticationToken authAs(String... authorities) {
        return new UsernamePasswordAuthenticationToken("admin", "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    private static final AdminReportContextResponse EMPTY =
            new AdminReportContextResponse(null, null, null, false, List.of(), List.of(), null, null);

    @Test
    void context_rendLeContexte_etTransmetLeDroitDeLireLesConversations() {
        UUID id = UUID.randomUUID();
        ReportEntity report = new ReportEntity();
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(contextService.build(report, true)).thenReturn(EMPTY);

        var resp = controller().context(id, authAs("REPORT_VIEW", "MODERATION_VIEW"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isSameAs(EMPTY);
    }

    @Test
    void context_sansMODERATION_VIEW_lesConversationsNeSontPasLisibles() {
        UUID id = UUID.randomUUID();
        ReportEntity report = new ReportEntity();
        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(contextService.build(report, false)).thenReturn(EMPTY);

        assertThat(controller().context(id, authAs("REPORT_VIEW")).getBody()).isSameAs(EMPTY);
    }

    @Test
    void context_signalementInconnuOuSupprime_404() {
        UUID id = UUID.randomUUID();
        when(reportRepo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller().context(id, authAs("REPORT_VIEW")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(contextService);
    }
}
