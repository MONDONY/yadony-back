package com.yadony.api.payments.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.dto.PawapayOperationSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PawapayReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-10-09T04:30:00Z");

    private final PawapayOperationRepository repository = mock(PawapayOperationRepository.class);
    private final PawapayClient client = mock(PawapayClient.class);
    private final PawapayReconciler reconciler = new PawapayReconciler(repository, client, new ObjectMapper());

    private static PawapayOperationEntity op(PawapayOperationStatus status, String amount) {
        PawapayOperationEntity op = mock(PawapayOperationEntity.class);
        when(op.getId()).thenReturn(UUID.randomUUID());
        when(op.getKind()).thenReturn(PawapayOperationKind.DEPOSIT);
        when(op.getStatus()).thenReturn(status);
        when(op.getAmount()).thenReturn(new BigDecimal(amount));
        when(op.getCurrency()).thenReturn("XOF");
        return op;
    }

    private static PawapayOperationSnapshot remote(PawapayOperationStatus status, String amount, String currency) {
        String raw = "{\"status\":\"" + status.name() + "\",\"amount\":\"" + amount + "\",\"currency\":\"" + currency + "\"}";
        return new PawapayOperationSnapshot(status, null, null, "txn-1", null, raw);
    }

    private void localOps(PawapayOperationEntity... ops) {
        when(repository.findByStatusInAndFinalizedAtAfter(anyCollection(), any())).thenReturn(List.of(ops));
    }

    @Test
    void operationIdentiqueDesDeuxCotes_estCoherente() {
        PawapayOperationEntity op = op(PawapayOperationStatus.COMPLETED, "15000");
        localOps(op);
        when(client.getStatus(PawapayOperationKind.DEPOSIT, op.getId()))
                .thenReturn(Optional.of(remote(PawapayOperationStatus.COMPLETED, "15000", "XOF")));

        ReconciliationResult result = reconciler.reconcile(NOW);

        assertThat(result.mismatches()).isEmpty();
        assertThat(result.checked()).isEqualTo(1);
    }

    @Test
    void echoueeChezNousMaisReussieChezPawapay_estSignalee() {
        // L'argent a bougé chez pawaPay alors que la base croit l'opération échouée.
        PawapayOperationEntity op = op(PawapayOperationStatus.FAILED, "15000");
        localOps(op);
        when(client.getStatus(PawapayOperationKind.DEPOSIT, op.getId()))
                .thenReturn(Optional.of(remote(PawapayOperationStatus.COMPLETED, "15000", "XOF")));

        assertThat(reconciler.reconcile(NOW).mismatches()).singleElement()
                .satisfies(m -> {
                    assertThat(m.reference()).isEqualTo(op.getId().toString());
                    assertThat(m.code()).contains("STATUT_DIFFERENT");
                });
    }

    @Test
    void montantOuDeviseDifferents_sontSignales() {
        PawapayOperationEntity op = op(PawapayOperationStatus.COMPLETED, "15000");
        localOps(op);
        when(client.getStatus(PawapayOperationKind.DEPOSIT, op.getId()))
                .thenReturn(Optional.of(remote(PawapayOperationStatus.COMPLETED, "1500", "XAF")));

        assertThat(reconciler.reconcile(NOW).mismatches()).singleElement()
                .satisfies(m -> assertThat(m.code()).contains("MONTANT_DIFFERENT").contains("DEVISE_DIFFERENTE"));
    }

    @Test
    void reussieChezNousMaisInconnueDePawapay_estSignalee() {
        PawapayOperationEntity op = op(PawapayOperationStatus.COMPLETED, "15000");
        localOps(op);
        when(client.getStatus(PawapayOperationKind.DEPOSIT, op.getId())).thenReturn(Optional.empty());

        assertThat(reconciler.reconcile(NOW).mismatches()).singleElement()
                .satisfies(m -> assertThat(m.code()).contains("INCONNUE_CHEZ_PAWAPAY"));
    }

    @Test
    void pawapayIndisponible_estCompteSansBloquerLesAutres() {
        PawapayOperationEntity down = op(PawapayOperationStatus.COMPLETED, "15000");
        PawapayOperationEntity fine = op(PawapayOperationStatus.COMPLETED, "15000");
        localOps(down, fine);
        when(client.getStatus(PawapayOperationKind.DEPOSIT, down.getId()))
                .thenThrow(new RestClientException("pawaPay 503"));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, fine.getId()))
                .thenReturn(Optional.of(remote(PawapayOperationStatus.COMPLETED, "15000", "XOF")));

        ReconciliationResult result = reconciler.reconcile(NOW);

        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.mismatches()).isEmpty();
    }
}
