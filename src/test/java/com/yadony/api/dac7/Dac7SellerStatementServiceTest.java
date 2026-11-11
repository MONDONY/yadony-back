package com.yadony.api.dac7;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.Dac7DeclarationService.Declaration;
import com.yadony.api.dac7.Dac7DeclarationService.Seller;
import com.yadony.api.dac7.Dac7Report.QuarterReport;
import com.yadony.api.dac7.Dac7Report.TravelerReport;
import com.yadony.api.dac7.dto.Dac7StatementsResponse;
import com.yadony.api.dac7.events.Dac7TaxStatementEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Récapitulatifs DAC7 : à la demande de l'équipe, un seul par voyageur et par année")
class Dac7SellerStatementServiceTest {

    private final Dac7DeclarationService declarations = mock(Dac7DeclarationService.class);
    private final Dac7AdminReadModel reads = mock(Dac7AdminReadModel.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AuditService audit = mock(AuditService.class);
    private final UUID admin = UUID.randomUUID();
    private final Set<String> reserved = new HashSet<>();
    private final Dac7SellerStatementService service = new Dac7SellerStatementService(declarations, reads, events,
            audit, TransactionOperations.withoutTransaction(),
            Clock.fixed(Instant.parse("2027-01-20T10:00:00Z"), ZoneOffset.UTC));

    private static Seller seller(List<String> exclusions) {
        UUID id = UUID.randomUUID();
        List<QuarterReport> q = List.of(
                new QuarterReport(1, 1, List.of(), new BigDecimal("95.50"), new BigDecimal("5.00"), true, true),
                new QuarterReport(2, 0, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, true),
                new QuarterReport(3, 0, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, true),
                new QuarterReport(4, 0, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, true));
        return new Seller(id, "Awa", "FR", true, Dac7IdentityStatus.COMPLETE, null, 0, null,
                new TravelerReport(id, "FR", true, Dac7IdentityStatus.COMPLETE, q), 1, new BigDecimal("95.50"),
                new BigDecimal("5.00"), true, true, exclusions, false);
    }

    @BeforeEach
    void setUp() {
        when(declarations.requireClosedYear(2026)).thenReturn(2026);
        when(declarations.collectionEnabled()).thenReturn(true);
        when(reads.reserveStatement(any(), anyInt(), any(), any()))
                .thenAnswer(inv -> reserved.add(inv.getArgument(0) + "/" + inv.getArgument(1)));
    }

    @Test
    @DisplayName("envoyé aux voyageurs déclarés seulement, jamais deux fois ; audit des compteurs")
    void idempotent() {
        Seller ok = seller(List.of());
        Seller excluded = seller(List.of("TAX_INFO_MISSING"));
        when(declarations.declaration(2026, true)).thenReturn(new Declaration(2026, null, List.of(ok, excluded),
                List.of(), List.of(), List.of(), Map.of(), 0));

        assertThat(service.send(2026, admin)).isEqualTo(new Dac7StatementsResponse(1, 1));
        assertThat(service.send(2026, admin)).isEqualTo(new Dac7StatementsResponse(0, 2));

        ArgumentCaptor<Dac7TaxStatementEvent> event = ArgumentCaptor.forClass(Dac7TaxStatementEvent.class);
        verify(events, times(1)).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(ok.userId());
        assertThat(event.getValue().quarters().get(0)).isEqualTo(new Dac7TaxStatementEvent.Quarter(1, 96, 1, 5));
        verify(reads, never()).reserveStatement(eq(excluded.userId()), anyInt(), any(), any());
        verify(audit, times(2)).log(eq("DAC7_STATEMENTS"), any(), eq("DAC7_STATEMENTS_SENT"), eq(admin), any());
    }

    @Test
    @DisplayName("année non close (le 20/12 pour l'année en cours) : 422 dac7-year-not-closed, rien n'est réservé")
    void anneeNonClose() {
        when(declarations.requireClosedYear(2027)).thenThrow(new YadonyBusinessException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "dac7-year-not-closed", "x", "x"));
        assertThatThrownBy(() -> service.send(2027, admin)).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("dac7-year-not-closed"));
        verify(reads, never()).reserveStatement(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("collecte éteinte : 409, rien n'est calculé ni envoyé")
    void eteint() {
        when(declarations.collectionEnabled()).thenReturn(false);
        assertThatThrownBy(() -> service.send(2026, admin)).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("dac7-collection-disabled"));
        verify(declarations, never()).declaration(anyInt(), anyBoolean());
        verify(events, never()).publishEvent(any());
    }
}
