package com.yadony.api.notifications;

import com.yadony.api.cancellation.events.CancellationFeePaidOutEvent;
import com.yadony.api.cancellation.events.SenderCancellationFeeRetainedEvent;
import com.yadony.api.cancellation.fee.CancellationFeeReason;
import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Frais d'annulation : l'expéditeur apprend ce qui lui a été retenu et ce qui lui est rendu (y compris après une absence
 * confirmée, où il n'a vu aucun aperçu), le voyageur apprend que son dédommagement est versé. Montants dans la devise du
 * paiement, au format affiché partout ailleurs ({@code WalletAmountText}).
 */
@ExtendWith(MockitoExtension.class)
class CancellationFeeNotificationListenerTest {

    private static final char NBSP = '\u00A0';

    @Mock private NotificationDispatcher dispatcher;
    @InjectMocks private CancellationFeeNotificationListener listener;

    private final UUID feeId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();

    // ── Expéditeur : retenue réglée ─────────────────────────────────────────────────────────────────────

    @Test
    void retained_senderCancel_inFrench_withBothAmounts() {
        when(dispatcher.messagesFor(senderId)).thenReturn(TestMessages.fr());

        listener.onRetained(retained(CancellationFeeReason.SENDER_CANCEL, "30.00", "70.00", "EUR"));

        verify(dispatcher).notifyUser(eq(senderId), eq("Frais d'annulation"),
                eq("Frais de 30,00" + NBSP + "€ retenus, 70,00" + NBSP + "€ vous sont remboursés."),
                eq(Map.of("type", "CANCELLATION_FEE_RETAINED", "bidId", bidId.toString(), "currency", "EUR")));
    }

    @Test
    void retained_senderCancel_inEnglish_withBothAmounts() {
        when(dispatcher.messagesFor(senderId)).thenReturn(TestMessages.en());

        listener.onRetained(retained(CancellationFeeReason.SENDER_CANCEL_AFTER_HANDOVER, "30.00", "70.00", "EUR"));

        verify(dispatcher).notifyUser(eq(senderId), eq("Cancellation fee"),
                eq("Fee of 30,00" + NBSP + "€ kept, 70,00" + NBSP + "€ refunded to you."), anyMap());
    }

    @Test
    void retained_noShowConfirmed_inFrench_saysTheMissedHandover_xofAmounts() {
        when(dispatcher.messagesFor(senderId)).thenReturn(TestMessages.fr());

        listener.onRetained(retained(CancellationFeeReason.SENDER_NO_SHOW, "19678", "45922", "XOF"));

        verify(dispatcher).notifyUser(eq(senderId), eq("Frais d'annulation"),
                eq("Absence confirmée : 19" + NBSP + "678" + NBSP + "F CFA retenus, 45" + NBSP + "922" + NBSP
                        + "F CFA remboursés."),
                eq(Map.of("type", "CANCELLATION_FEE_RETAINED", "bidId", bidId.toString(), "currency", "XOF")));
    }

    @Test
    void retained_noShowConfirmed_inEnglish() {
        when(dispatcher.messagesFor(senderId)).thenReturn(TestMessages.en());

        listener.onRetained(retained(CancellationFeeReason.SENDER_NO_SHOW, "19678", "45922", "XOF"));

        verify(dispatcher).notifyUser(eq(senderId), eq("Cancellation fee"),
                eq("Missed handover: fee of 19" + NBSP + "678" + NBSP + "F CFA kept, 45" + NBSP + "922" + NBSP
                        + "F CFA refunded."), anyMap());
    }

    @Test
    void retained_nothingRetained_noNotification() {
        listener.onRetained(retained(CancellationFeeReason.SENDER_CANCEL, "0.00", "100.00", "EUR"));
        listener.onRetained(new SenderCancellationFeeRetainedEvent(feeId, bidId, null, CancellationFeeReason.SENDER_CANCEL,
                new BigDecimal("30.00"), new BigDecimal("70.00"), "EUR"));

        verifyNoInteractions(dispatcher);
    }

    // ── Voyageur : dédommagement versé ─────────────────────────────────────────────────────────────────────

    @Test
    void paidOut_inFrench_withTheNetAmount() {
        when(dispatcher.messagesFor(travelerId)).thenReturn(TestMessages.fr());

        listener.onPaidOut(new CancellationFeePaidOutEvent(feeId, bidId, travelerId, new BigDecimal("26.84"), "EUR"));

        verify(dispatcher).notifyUser(eq(travelerId), eq("Dédommagement reçu"),
                eq("26,84" + NBSP + "€ vous sont versés en dédommagement de l'annulation."),
                eq(Map.of("type", "CANCELLATION_FEE_PAID_OUT", "bidId", bidId.toString(), "currency", "EUR")));
    }

    @Test
    void paidOut_inEnglish_xof() {
        when(dispatcher.messagesFor(travelerId)).thenReturn(TestMessages.en());

        listener.onPaidOut(new CancellationFeePaidOutEvent(feeId, bidId, travelerId, new BigDecimal("16662"), "XOF"));

        verify(dispatcher).notifyUser(eq(travelerId), eq("Compensation received"),
                eq("16" + NBSP + "662" + NBSP + "F CFA paid to you as compensation for the cancellation."), anyMap());
    }

    @Test
    void paidOut_nothingToPay_noNotification() {
        // Petit colis : frais de remboursement et commission ont tout pris, rien n'a été versé.
        listener.onPaidOut(new CancellationFeePaidOutEvent(feeId, bidId, travelerId, new BigDecimal("0.00"), "EUR"));
        listener.onPaidOut(new CancellationFeePaidOutEvent(feeId, bidId, null, new BigDecimal("5.00"), "EUR"));

        verifyNoInteractions(dispatcher);
        verify(dispatcher, never()).notifyUser(any(), anyString(), anyString(), anyMap());
    }

    // ── Contrat : après commit, à part, dans une transaction neuve ; classement des types ────────────────────

    @Test
    void listeners_runAfterCommit_inTheirOwnTransaction() throws NoSuchMethodException {
        for (Method m : new Method[] {
                CancellationFeeNotificationListener.class.getMethod("onRetained", SenderCancellationFeeRetainedEvent.class),
                CancellationFeeNotificationListener.class.getMethod("onPaidOut", CancellationFeePaidOutEvent.class)}) {
            assertThat(m.getAnnotation(TransactionalEventListener.class).phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
            assertThat(m.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
            assertThat(m.isAnnotationPresent(org.springframework.scheduling.annotation.Async.class)).isTrue();
        }
    }

    @Test
    void types_areMoney_alwaysOn_andOpenTheParcel() {
        for (String type : new String[] {"CANCELLATION_FEE_RETAINED", "CANCELLATION_FEE_PAID_OUT"}) {
            assertThat(NotificationCategory.fromType(type)).isEqualTo(NotificationCategory.PAIEMENTS);
            assertThat(NotificationPrefsService.isAlwaysOn(type)).isTrue();
            assertThat(NotificationDeeplink.of(type, Map.of("bidId", bidId.toString())))
                    .contains("yadony://bids/" + bidId);
        }
    }

    private SenderCancellationFeeRetainedEvent retained(CancellationFeeReason reason, String retained, String refunded,
                                                       String currency) {
        return new SenderCancellationFeeRetainedEvent(feeId, bidId, senderId, reason, new BigDecimal(retained),
                new BigDecimal(refunded), currency);
    }
}
