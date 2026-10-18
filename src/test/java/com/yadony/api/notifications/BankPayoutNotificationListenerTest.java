package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.events.BankPayoutFailedEvent;
import com.yadony.api.payments.events.BankPayoutPaidEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankPayoutNotificationListenerTest {

    private static final char NBSP = ' ';

    @Mock private NotificationDispatcher notificationDispatcher;
    @InjectMocks private BankPayoutNotificationListener listener;

    private final UUID travelerId = UUID.randomUUID();

    @Test
    void paid_inFrench_withAmount() {
        when(notificationDispatcher.messagesFor(travelerId)).thenReturn(TestMessages.fr());

        listener.onPaid(new BankPayoutPaidEvent(travelerId, "po_1", new BigDecimal("5.00"), "EUR",
                LocalDate.of(2026, 10, 24)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationDispatcher).notifyUser(eq(travelerId), eq("Votre argent est arrivé"), body.capture(),
                data.capture());
        assertThat(body.getValue()).contains("5").contains("€").endsWith("sont arrivés sur votre compte bancaire.");
        assertThat(data.getValue()).containsEntry("type", "BANK_PAYOUT_PAID").containsEntry("payoutId", "po_1");
    }

    @Test
    void paid_inEnglish_withoutKnownCurrency() {
        when(notificationDispatcher.messagesFor(travelerId)).thenReturn(TestMessages.en());

        listener.onPaid(new BankPayoutPaidEvent(travelerId, null, null, null, null));

        verify(notificationDispatcher).notifyUser(eq(travelerId), eq("Your money has arrived"),
                eq("Your transfer has reached your bank account."), eq(Map.of("type", "BANK_PAYOUT_PAID")));
    }

    @Test
    void failed_pointsToBankAccountUpdate() {
        when(notificationDispatcher.messagesFor(travelerId)).thenReturn(TestMessages.fr());

        listener.onFailed(new BankPayoutFailedEvent(travelerId, "po_2", "account_closed", new BigDecimal("5"), "EUR"));

        verify(notificationDispatcher).notifyUser(eq(travelerId), eq("Virement bancaire échoué"),
                eq("Virement échoué : mettez à jour votre compte bancaire dans Paiements."),
                eq(Map.of("type", "BANK_PAYOUT_FAILED", "payoutId", "po_2", "failureCode", "account_closed")));
    }

    @Test
    void failed_inEnglish_withoutCode() {
        when(notificationDispatcher.messagesFor(travelerId)).thenReturn(TestMessages.en());

        listener.onFailed(new BankPayoutFailedEvent(travelerId, "po_3", null, null, null));

        verify(notificationDispatcher).notifyUser(eq(travelerId), eq("Bank transfer failed"),
                eq("Transfer failed: update your bank account in Payments."),
                eq(Map.of("type", "BANK_PAYOUT_FAILED", "payoutId", "po_3")));
    }
}
