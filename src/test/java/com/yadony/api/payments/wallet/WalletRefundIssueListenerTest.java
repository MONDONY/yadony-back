package com.yadony.api.payments.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WalletRefundIssueListenerTest {

    private final WalletSelfRefundService service = mock(WalletSelfRefundService.class);
    private final WalletRefundIssueListener listener = new WalletRefundIssueListener(service);

    @Test
    void onItemsCreated_emetLesItemsDeLaDemande() {
        UUID requestId = UUID.randomUUID();

        listener.onItemsCreated(new WalletRefundItemsCreatedEvent(requestId));

        verify(service).issuePendingItems(requestId);
    }

    @Test
    void onItemsCreated_erreur_avaleeCarLaRepriseRattrapera() {
        UUID requestId = UUID.randomUUID();
        doThrow(new IllegalStateException("base indisponible")).when(service).issuePendingItems(requestId);

        assertThatCode(() -> listener.onItemsCreated(new WalletRefundItemsCreatedEvent(requestId)))
                .doesNotThrowAnyException();
    }

    @Test
    void onItemsCreated_neSeDeclencheQuApresCommit() throws NoSuchMethodException {
        TransactionalEventListener annotation = WalletRefundIssueListener.class
                .getMethod("onItemsCreated", WalletRefundItemsCreatedEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
