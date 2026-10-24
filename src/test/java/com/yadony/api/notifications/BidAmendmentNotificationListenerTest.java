package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.amendment.BidAmendmentClosedReason;
import com.yadony.api.payments.amendment.BidAmendmentStatus;
import com.yadony.api.payments.amendment.events.BidAmendmentAcceptedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentClosedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentReminderDueEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRequestedEvent;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BidAmendmentNotificationListenerTest {

    private static final char NBSP = '\u00a0';

    @Mock NotificationDispatcher dispatcher;
    @InjectMocks BidAmendmentNotificationListener listener;

    private final UUID amendmentId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // L'expéditeur lit en français, le voyageur en anglais : chaque texte part dans la langue de son destinataire.
        lenient().when(dispatcher.messagesFor(senderId)).thenReturn(TestMessages.fr());
        lenient().when(dispatcher.messagesFor(travelerId)).thenReturn(TestMessages.en());
    }

    private Map<String, String> data(String type) {
        return Map.of("type", type, "bidId", bidId.toString(), "amendmentId", amendmentId.toString());
    }

    @Test
    void requested_notifiesTravelerWithDeeplink() {
        listener.onRequested(new BidAmendmentRequestedEvent(amendmentId, bidId, senderId, travelerId,
                new BigDecimal("26.50"), new BigDecimal("23.50"), "EUR",
                LocalDateTime.of(2026, 10, 27, 12, 30), "Europe/Paris"));

        // Le voyageur ne voit que son net, l'heure limite est lue dans le fuseau du trajet (12:30 UTC, 13:30 à Paris).
        verify(dispatcher).notifyUser(eq(travelerId), eq("Parcel change requested"),
                eq("Parcel add-on: +23,50" + NBSP + "€ for you. Reply by Oct 27, 13:30."),
                eq(data("BID_AMENDMENT_REQUESTED")));
        verify(dispatcher, never()).notifyUser(eq(senderId), anyString(), anyString(), any());
        assertThat(NotificationDeeplink.of("BID_AMENDMENT_REQUESTED", data("BID_AMENDMENT_REQUESTED")))
                .contains("yadony://bids/" + bidId + "?action=amendment");
    }

    @Test
    void requested_neverShowsTheGrossToTheTraveler() {
        listener.onRequested(new BidAmendmentRequestedEvent(amendmentId, bidId, senderId, travelerId,
                new BigDecimal("26.50"), new BigDecimal("23.50"), "EUR",
                LocalDateTime.of(2026, 10, 27, 12, 30), "Europe/Paris"));

        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(dispatcher).notifyUser(eq(travelerId), anyString(), body.capture(), any());
        assertThat(body.getValue()).doesNotContain("26,50");
    }

    @Test
    void reminder_notifiesTraveler() {
        listener.onReminderDue(new BidAmendmentReminderDueEvent(amendmentId, bidId, travelerId));

        verify(dispatcher).notifyUser(eq(travelerId), eq("Parcel change waiting"),
                eq("The sender awaits your reply. The request expires soon."), eq(data("BID_AMENDMENT_REMINDER")));
        assertThat(NotificationDeeplink.of("BID_AMENDMENT_REMINDER", data("BID_AMENDMENT_REMINDER")))
                .contains("yadony://bids/" + bidId + "?action=amendment");
    }

    @Test
    void accepted_mobileMoney_bodyAsksToPay() {
        listener.onAccepted(new BidAmendmentAcceptedEvent(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.PAWAPAY, true));

        verify(dispatcher).notifyUser(eq(senderId), eq("Modification acceptée"),
                eq("Payez maintenant pour que l'ajout soit confirmé."), eq(data("BID_AMENDMENT_ACCEPTED")));
        verify(dispatcher, never()).notifyUser(eq(travelerId), anyString(), anyString(), any());
        assertThat(NotificationDeeplink.of("BID_AMENDMENT_ACCEPTED", data("BID_AMENDMENT_ACCEPTED")))
                .contains("yadony://bids/" + bidId + "?action=amendment");
    }

    @Test
    void accepted_cardCashAndPaidMobileMoney_haveTheirOwnBody() {
        listener.onAccepted(new BidAmendmentAcceptedEvent(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, false));
        verify(dispatcher).notifyUser(eq(senderId), eq("Modification acceptée"),
                eq("Le voyageur a accepté. Le montant bloqué sur votre carte est débité."),
                eq(data("BID_AMENDMENT_ACCEPTED")));

        listener.onAccepted(new BidAmendmentAcceptedEvent(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.CASH, false));
        verify(dispatcher).notifyUser(eq(senderId), eq("Modification acceptée"),
                eq("Le voyageur a accepté. Remettez le complément en espèces à la remise."),
                eq(data("BID_AMENDMENT_ACCEPTED")));

        listener.onAccepted(new BidAmendmentAcceptedEvent(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.PAWAPAY, false));
        verify(dispatcher).notifyUser(eq(senderId), eq("Modification acceptée"),
                eq("Le voyageur a accepté l'ajout. Votre colis est agrandi."), eq(data("BID_AMENDMENT_ACCEPTED")));
    }

    @Test
    void declined_notifiesSender_cardHoldReleased() {
        listener.onClosed(new BidAmendmentClosedEvent.Declined(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.PAWAPAY, BidAmendmentStatus.PROPOSED, BidAmendmentClosedReason.DECLINED_BY_TRAVELER));
        verify(dispatcher).notifyUser(eq(senderId), eq("Modification refusée"),
                eq("Le voyageur a refusé. Votre colis reste tel que prévu."), eq(data("BID_AMENDMENT_DECLINED")));

        listener.onClosed(new BidAmendmentClosedEvent.Declined(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, BidAmendmentStatus.PROPOSED, BidAmendmentClosedReason.DECLINED_BY_TRAVELER));
        verify(dispatcher).notifyUser(eq(senderId), eq("Modification refusée"),
                eq("Refusé par le voyageur. Montant bloqué libéré, colis inchangé."),
                eq(data("BID_AMENDMENT_DECLINED")));
        verify(dispatcher, never()).notifyUser(eq(travelerId), anyString(), anyString(), any());
    }

    @Test
    void expired_notifiesBoth() {
        listener.onClosed(new BidAmendmentClosedEvent.Expired(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.CASH, BidAmendmentStatus.PROPOSED, BidAmendmentClosedReason.EXPIRED));

        verify(dispatcher).notifyUser(eq(travelerId), eq("Change request expired"),
                eq("You did not reply: the change request expired."), eq(data("BID_AMENDMENT_EXPIRED")));
        verify(dispatcher).notifyUser(eq(senderId), eq("Modification expirée"),
                eq("Sans réponse du voyageur, la modification a expiré."), eq(data("BID_AMENDMENT_EXPIRED")));
    }

    @Test
    void expired_cardHold_saysTheAmountIsReleased() {
        listener.onClosed(new BidAmendmentClosedEvent.Expired(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, BidAmendmentStatus.PROPOSED, BidAmendmentClosedReason.EXPIRED));

        verify(dispatcher).notifyUser(eq(senderId), eq("Modification expirée"),
                eq("Sans réponse du voyageur : expirée, montant bloqué libéré."), eq(data("BID_AMENDMENT_EXPIRED")));
    }

    @Test
    void expired_paymentNotReceived_blamesThePayment() {
        listener.onClosed(new BidAmendmentClosedEvent.Expired(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.PAWAPAY, BidAmendmentStatus.PAYMENT_PENDING,
                BidAmendmentClosedReason.PAYMENT_NOT_RECEIVED));

        verify(dispatcher).notifyUser(eq(senderId), eq("Modification expirée"),
                eq("Paiement non reçu à temps : la modification est annulée."), eq(data("BID_AMENDMENT_EXPIRED")));
        verify(dispatcher).notifyUser(eq(travelerId), eq("Change request expired"),
                eq("The sender did not pay in time: change cancelled."), eq(data("BID_AMENDMENT_EXPIRED")));
    }

    @Test
    void cancelledBySender_afterItWasProposed_notifiesTravelerOnly() {
        listener.onClosed(new BidAmendmentClosedEvent.Cancelled(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, BidAmendmentStatus.PROPOSED, BidAmendmentClosedReason.CANCELLED_BY_SENDER));

        verify(dispatcher).notifyUser(eq(travelerId), eq("Change request cancelled"),
                eq("The sender cancelled their change request."), eq(data("BID_AMENDMENT_CANCELLED")));
        verify(dispatcher, never()).notifyUser(eq(senderId), anyString(), anyString(), any());
        assertThat(NotificationDeeplink.of("BID_AMENDMENT_CANCELLED", data("BID_AMENDMENT_CANCELLED")))
                .contains("yadony://bids/" + bidId);
    }

    @Test
    void cancelledWhileAcceptancePending_travelerKnewAboutIt() {
        // Le voyageur avait accepté (paiement ou commission en attente) : il est prévenu.
        listener.onClosed(new BidAmendmentClosedEvent.Cancelled(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.CASH, BidAmendmentStatus.COMMISSION_PENDING,
                BidAmendmentClosedReason.COMMISSION_NOT_CONFIRMED));
        verify(dispatcher).notifyUser(eq(travelerId), eq("Change request cancelled"),
                eq("Commission not confirmed in time: request cancelled."), eq(data("BID_AMENDMENT_CANCELLED")));
        // L'expéditeur attendait une réponse : il apprend que sa demande est annulée, sans montant.
        verify(dispatcher).notifyUser(eq(senderId), eq("Modification annulée"),
                eq("Le voyageur n'a pas pu finaliser : votre demande est annulée."),
                eq(data("BID_AMENDMENT_CANCELLED")));

        listener.onClosed(new BidAmendmentClosedEvent.Cancelled(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.PAWAPAY, BidAmendmentStatus.PAYMENT_PENDING,
                BidAmendmentClosedReason.CANCELLED_BY_SENDER));
        verify(dispatcher).notifyUser(eq(travelerId), eq("Change request cancelled"),
                eq("The sender cancelled their change request."), eq(data("BID_AMENDMENT_CANCELLED")));
        verify(dispatcher, never()).notifyUser(eq(senderId), eq("Modification annulée"),
                eq("L'acceptation n'a pas abouti : votre carte n'a pas été débitée."), any());
    }

    @Test
    void cancelledBeforeItWasPresented_travelerNeverKnew() {
        // Carte encore en autorisation : annulée par l'expéditeur, ou autorisation jamais confirmée.
        listener.onClosed(new BidAmendmentClosedEvent.Cancelled(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, BidAmendmentStatus.AUTHORIZATION_PENDING,
                BidAmendmentClosedReason.CANCELLED_BY_SENDER));
        listener.onClosed(new BidAmendmentClosedEvent.Cancelled(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, BidAmendmentStatus.AUTHORIZATION_PENDING,
                BidAmendmentClosedReason.AUTHORIZATION_NOT_CONFIRMED));

        verifyNoInteractions(dispatcher);
    }

    @Test
    void cancelledOnBidEnd_travelerGetsTheNeutralText() {
        listener.onClosed(new BidAmendmentClosedEvent.Cancelled(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, BidAmendmentStatus.PROPOSED, BidAmendmentClosedReason.BID_ENDED));

        verify(dispatcher).notifyUser(eq(travelerId), eq("Change request cancelled"),
                eq("The change request is cancelled."), eq(data("BID_AMENDMENT_CANCELLED")));
        verify(dispatcher, never()).notifyUser(eq(senderId), anyString(), anyString(), any());
    }

    @Test
    void captureFailed_notifiesSenderAndTraveler() {
        listener.onClosed(new BidAmendmentClosedEvent.Cancelled(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, BidAmendmentStatus.PROPOSED, BidAmendmentClosedReason.CAPTURE_FAILED));

        verify(dispatcher).notifyUser(eq(senderId), eq("Modification annulée"),
                eq("L'acceptation n'a pas abouti : votre carte n'a pas été débitée."),
                eq(data("BID_AMENDMENT_CANCELLED")));
        verify(dispatcher).notifyUser(eq(travelerId), eq("Change request cancelled"),
                eq("The sender's payment failed: request cancelled."), eq(data("BID_AMENDMENT_CANCELLED")));
    }

    @Test
    void notBrought_notifiesSender_refundMentionedWhenPaidOnline() {
        listener.onClosed(new BidAmendmentClosedEvent.NotBrought(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.CASH, BidAmendmentStatus.ACCEPTED, BidAmendmentClosedReason.NOT_BROUGHT));
        verify(dispatcher).notifyUser(eq(senderId), eq("Ajout non apporté"),
                eq("Ajout non apporté à la remise : modification annulée."), eq(data("BID_AMENDMENT_NOT_BROUGHT")));

        listener.onClosed(new BidAmendmentClosedEvent.NotBrought(amendmentId, bidId, senderId, travelerId,
                BidSupplementRail.STRIPE, BidAmendmentStatus.ACCEPTED, BidAmendmentClosedReason.NOT_BROUGHT));
        verify(dispatcher).notifyUser(eq(senderId), eq("Ajout non apporté"),
                eq("Ajout non apporté : modification annulée, remboursement en cours."),
                eq(data("BID_AMENDMENT_NOT_BROUGHT")));
        verify(dispatcher, never()).notifyUser(eq(travelerId), anyString(), anyString(), any());
    }

    @Test
    void refunded_senderGetsTheGross_travelerGetsTheCommission() {
        listener.onRefunded(new BidAmendmentRefundedEvent(amendmentId, bidId, senderId,
                new BigDecimal("26.50"), "EUR", false));
        verify(dispatcher).notifyUser(eq(senderId), eq("Modification remboursée"),
                eq("La modification de 26,50" + NBSP + "€ vous est remboursée."), eq(data("BID_AMENDMENT_REFUNDED")));

        listener.onRefunded(new BidAmendmentRefundedEvent(amendmentId, bidId, travelerId,
                new BigDecimal("3.00"), "EUR", true));
        verify(dispatcher).notifyUser(eq(travelerId), eq("Parcel change refunded"),
                eq("The parcel change commission (3,00" + NBSP + "€) has been returned to you."),
                eq(data("BID_AMENDMENT_REFUNDED")));
    }

    @Test
    void notCritical() throws IllegalAccessException {
        // Décision du contrôleur : aucun type de la modification n'est critique, donc jamais de SMS de repli.
        List<String> types = new ArrayList<>();
        for (Field f : BidAmendmentNotifications.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                types.add((String) f.get(null));
            }
        }
        assertThat(types).hasSize(8).allSatisfy(type -> {
            assertThat(type).startsWith("BID_AMENDMENT_");
            assertThat(NotificationTypes.isCritical(type)).as(type).isFalse();
            assertThat(NotificationTypes.CRITICAL).doesNotContain(type);
            assertThat(NotificationCategory.fromType(type)).as(type).isEqualTo(NotificationCategory.PAIEMENTS);
            assertThat(NotificationPrefsService.TYPE_TO_PREF).as(type).containsKey(type);
        });
    }
}
