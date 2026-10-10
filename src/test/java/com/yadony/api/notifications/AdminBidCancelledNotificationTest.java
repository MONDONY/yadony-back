package com.yadony.api.notifications;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.events.AdminBidCancelledEvent;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.events.BidRejectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Colis annulé par l'administration : notifications des deux parties, jamais « Demande refusée ». */
@ExtendWith(MockitoExtension.class)
class AdminBidCancelledNotificationTest {

    @Mock FcmService fcmService;
    @Mock SmsService smsService;
    @Mock UserRepository userRepository;
    @Mock NotificationService notificationService;
    @Mock com.yadony.api.common.BlockVisibility blockVisibility;

    private NotificationDispatcher dispatcher;
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var pawapay = new com.yadony.api.payments.pawapay.PawapayProperties(true, "https://x", "t", false, 30,
                "https://api.test", "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting", "yadony://payments/wallet",
                new com.yadony.api.payments.pawapay.PawapayProperties.BalanceMin(BigDecimal.ZERO, BigDecimal.ZERO));
        dispatcher = new NotificationDispatcher(fcmService, smsService, userRepository, notificationService,
                blockVisibility, pawapay, TestMessages.resolver());
        var stub = new NotificationEntity(UUID.randomUUID(), "STUB", "stub", "stub", Map.of(), false);
        ReflectionTestUtils.setField(stub, "id", UUID.randomUUID());
        lenient().when(notificationService.persist(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(stub);
    }

    private AdminBidCancelledEvent event(UUID traveler, boolean refund, boolean withTraveler) {
        return new AdminBidCancelledEvent(bidId, UUID.randomUUID(), senderId, traveler, UUID.randomUUID(),
                refund, BigDecimal.TEN, "EUR", withTraveler);
    }

    @Test
    void previentLExpediteurRembourseEtLeVoyageur() {
        dispatcher.onAdminBidCancelled(event(travelerId, true, false));

        verify(fcmService).sendToUser(eq(senderId), eq("Colis annulé par Yadony"),
                eq("L'équipe Yadony a annulé votre colis. Vous êtes remboursé intégralement."),
                argThat(d -> "BID_CANCELLED_BY_ADMIN".equals(d.get("type")) && bidId.toString().equals(d.get("bidId"))));
        verify(fcmService).sendToUser(eq(travelerId), eq("Colis annulé par Yadony"),
                eq("Yadony a annulé un colis de votre trajet. Rien à faire de votre côté."), any());
    }

    @Test
    void colisChezLeVoyageur_sansRemboursement_libellesDedies() {
        dispatcher.onAdminBidCancelled(event(travelerId, false, true));

        verify(fcmService).sendToUser(eq(senderId), any(), eq("L'équipe Yadony a annulé votre colis."), any());
        verify(fcmService).sendToUser(eq(travelerId), any(),
                eq("Yadony a annulé un colis que vous portez. Le support vous contacte."),
                any());
    }

    @Test
    void sansVoyageurConnu_seulLExpediteur() {
        dispatcher.onAdminBidCancelled(event(null, true, false));

        verify(fcmService, times(1)).sendToUser(any(), any(), any(), any());
    }

    @Test
    void bidRejeteParLAdmin_pasDeNotificationDeRefus() {
        dispatcher.onBidRejected(new BidRejectedEvent(bidId, senderId,
                BidRejectedEvent.REASON_CANCELLED_BY_ADMIN, UUID.randomUUID(), false));

        verifyNoInteractions(fcmService, notificationService);
    }

    @Test
    void typeRangeEtLienProfond() {
        assertThat(NotificationPrefsService.TYPE_TO_PREF.get("BID_CANCELLED_BY_ADMIN")).isEqualTo("pushActivityBids");
        assertThat(NotificationDeeplink.of("BID_CANCELLED_BY_ADMIN", Map.of("bidId", bidId.toString())))
                .contains("yadony://bids/" + bidId);
    }
}
