package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.BidSupplementStatus;
import com.yadony.api.payments.supplement.events.BidSupplementDeclinedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementExpiredEvent;
import com.yadony.api.payments.supplement.events.BidSupplementPaidEvent;
import com.yadony.api.payments.supplement.events.BidSupplementPaidOutEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRequestedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementWithdrawnEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BidSupplementNotificationListenerTest {

    private static final char NBSP = ' ';

    @Mock NotificationDispatcher dispatcher;
    @InjectMocks BidSupplementNotificationListener listener;

    private final UUID supplementId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(dispatcher.messagesFor(senderId)).thenReturn(TestMessages.fr());
        lenient().when(dispatcher.messagesFor(travelerId)).thenReturn(TestMessages.en());
    }

    private Map<String, String> data(String type) {
        return Map.of("type", type, "bidId", bidId.toString(), "supplementId", supplementId.toString());
    }

    @Test
    void demande_expediteur_brutEtHeureLimiteDuTrajet() {
        listener.onRequested(new BidSupplementRequestedEvent(supplementId, bidId, senderId, travelerId,
                new BigDecimal("21.10"), "EUR", LocalDateTime.of(2026, 10, 19, 10, 0), "Europe/Paris"));

        verify(dispatcher).notifyUser(eq(senderId), eq("Supplément demandé"),
                eq("Le voyageur demande 21,10" + NBSP + "€ de plus. Répondez avant 12:00."),
                eq(data("BID_SUPPLEMENT_REQUESTED")));
        verify(dispatcher, never()).notifyUser(eq(travelerId), anyString(), anyString(), any());
    }

    @Test
    void paye_voyageur_carteOuEspeces() {
        listener.onPaid(new BidSupplementPaidEvent(supplementId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, new BigDecimal("21.10"), "EUR"));
        verify(dispatcher).notifyUser(eq(travelerId), eq("Extra charge paid"),
                eq("The sender paid 21,10" + NBSP + "€. You can scan the parcel."), eq(data("BID_SUPPLEMENT_PAID")));

        listener.onPaid(new BidSupplementPaidEvent(supplementId, bidId, senderId, travelerId,
                BidSupplementRail.CASH, new BigDecimal("13680"), "XOF"));
        verify(dispatcher).notifyUser(eq(travelerId), eq("Extra charge paid"),
                eq("The sender agrees to hand you 13" + NBSP + "680" + NBSP + "F CFA in cash."), eq(data("BID_SUPPLEMENT_PAID")));
    }

    @Test
    void refuse_voyageur() {
        listener.onDeclined(new BidSupplementDeclinedEvent(supplementId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, BidSupplementStatus.PROPOSED));
        verify(dispatcher).notifyUser(eq(travelerId), eq("Extra charge declined"),
                eq("Carry the parcel at the agreed price or refuse it, with no penalty."),
                eq(data("BID_SUPPLEMENT_DECLINED")));
    }

    @Test
    void expire_lesDeuxParties() {
        listener.onExpired(new BidSupplementExpiredEvent(supplementId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, BidSupplementStatus.PROPOSED));
        verify(dispatcher).notifyUser(eq(travelerId), eq("Extra charge expired"),
                eq("Carry the parcel at the agreed price or refuse it, with no penalty."),
                eq(data("BID_SUPPLEMENT_EXPIRED")));
        verify(dispatcher).notifyUser(eq(senderId), eq("Supplément expiré"),
                eq("La demande de supplément a expiré."), eq(data("BID_SUPPLEMENT_EXPIRED")));
    }

    @Test
    void retire_expediteur() {
        listener.onWithdrawn(new BidSupplementWithdrawnEvent(supplementId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, BidSupplementStatus.PROPOSED));
        verify(dispatcher).notifyUser(eq(senderId), eq("Supplément annulé"),
                eq("Le voyageur a retiré sa demande de supplément."), eq(data("BID_SUPPLEMENT_WITHDRAWN")));
    }

    @Test
    void retireAvantLaCommissionEspeces_expediteurJamaisPrevenu() {
        listener.onWithdrawn(new BidSupplementWithdrawnEvent(supplementId, bidId, senderId, travelerId,
                BidSupplementRail.CASH, BidSupplementStatus.COMMISSION_PENDING));
        org.mockito.Mockito.verifyNoInteractions(dispatcher);
    }

    @Test
    void rembourse_expediteurOuCommissionAuVoyageur() {
        listener.onRefunded(new BidSupplementRefundedEvent(supplementId, bidId, senderId,
                new BigDecimal("21.10"), "EUR", false));
        verify(dispatcher).notifyUser(eq(senderId), eq("Supplément remboursé"),
                eq("Le supplément de 21,10" + NBSP + "€ vous est remboursé."), eq(data("BID_SUPPLEMENT_REFUNDED")));

        listener.onRefunded(new BidSupplementRefundedEvent(supplementId, bidId, travelerId,
                new BigDecimal("1.10"), "EUR", true));
        verify(dispatcher).notifyUser(eq(travelerId), eq("Extra charge refunded"),
                eq("The extra charge commission (1,10" + NBSP + "€) has been returned to you."), eq(data("BID_SUPPLEMENT_REFUNDED")));
    }

    @Test
    void verse_voyageur() {
        listener.onPaidOut(new BidSupplementPaidOutEvent(supplementId, bidId, travelerId, new BigDecimal("20.00"), "EUR"));
        verify(dispatcher).notifyUser(eq(travelerId), eq("Extra charge paid out"),
                eq("20,00" + NBSP + "€ of extra charge has been paid out to you."), eq(data("BID_SUPPLEMENT_PAID_OUT")));
    }
}
