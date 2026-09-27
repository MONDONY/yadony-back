package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.wallet.WalletAdjustedByAdminEvent;
import com.yadony.api.payments.wallet.WalletAdjustmentDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletAdjustedByAdminNotificationListenerTest {

    private static final char NBSP = ' ';

    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private WalletAdjustedByAdminNotificationListener listener;

    private final UUID userId = UUID.randomUUID();
    private final UUID txId = UUID.randomUUID();

    @Test
    void creditEnFrancais_montantFormateSansMotifInterne() {
        when(notificationDispatcher.messagesFor(userId)).thenReturn(TestMessages.fr());

        listener.onWalletAdjusted(new WalletAdjustedByAdminEvent(userId, "XOF", WalletAdjustmentDirection.CREDIT,
                new BigDecimal("1500"), txId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationDispatcher).notifyUser(eq(userId), eq("Solde crédité"), body.capture(), data.capture());
        assertThat(body.getValue())
                .isEqualTo("Votre solde a été crédité de 1" + NBSP + "500" + NBSP + "F CFA par l'équipe Yadony.");
        assertThat(data.getValue())
                .containsEntry("type", "WALLET_ADJUSTED")
                .containsEntry("currency", "XOF")
                .containsEntry("transactionId", txId.toString())
                .doesNotContainKey("reason");
    }

    @Test
    void debitEnAnglais() {
        when(notificationDispatcher.messagesFor(userId)).thenReturn(TestMessages.en());

        listener.onWalletAdjusted(new WalletAdjustedByAdminEvent(userId, "EUR", WalletAdjustmentDirection.DEBIT,
                new BigDecimal("12.5"), txId));

        verify(notificationDispatcher).notifyUser(eq(userId), eq("Balance debited"),
                eq("The Yadony team debited 12,50" + NBSP + "€ from your balance."), any());
    }

    @Test
    void debitEnFrancais_etCreditEnAnglais() {
        when(notificationDispatcher.messagesFor(userId)).thenReturn(TestMessages.fr(), TestMessages.en());

        listener.onWalletAdjusted(new WalletAdjustedByAdminEvent(userId, "EUR", WalletAdjustmentDirection.DEBIT,
                new BigDecimal("3"), txId));
        listener.onWalletAdjusted(new WalletAdjustedByAdminEvent(userId, "EUR", WalletAdjustmentDirection.CREDIT,
                new BigDecimal("3"), txId));

        verify(notificationDispatcher).notifyUser(eq(userId), eq("Solde débité"),
                eq("Votre solde a été débité de 3,00" + NBSP + "€ par l'équipe Yadony."), any());
        verify(notificationDispatcher).notifyUser(eq(userId), eq("Balance credited"),
                eq("The Yadony team credited 3,00" + NBSP + "€ to your balance."), any());
    }

    @Test
    void typeRangeDansLesPaiements() {
        assertThat(NotificationCategory.fromType("WALLET_ADJUSTED")).isEqualTo(NotificationCategory.PAIEMENTS);
    }
}
