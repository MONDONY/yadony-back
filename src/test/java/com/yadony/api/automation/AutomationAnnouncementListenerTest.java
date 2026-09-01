package com.yadony.api.automation;

import com.yadony.api.matching.AnnouncementPublishedEvent;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AutomationAnnouncementListenerTest {

    @Mock private AutomationRuleRepository ruleRepository;
    @Mock private BidRepository bidRepository;
    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private AutomationActionExecutor executor;

    private AutomationAnnouncementListener listener;
    private UUID travelerId, senderId1, senderId2, announcementId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new AutomationAnnouncementListener(ruleRepository, bidRepository,
                notificationDispatcher, executor);
        travelerId = UUID.randomUUID();
        senderId1 = UUID.randomUUID();
        senderId2 = UUID.randomUUID();
        announcementId = UUID.randomUUID();
    }

    private AutomationRuleEntity loyalRule(boolean enabled) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setTravelerId(travelerId);
        r.setPresetRuleId("notify_loyal_senders");
        r.setEnabled(enabled);
        r.setAction(Map.of());
        return r;
    }

    @Test
    void onAnnouncementPublished_notifiesEachLoyalSenderWhenRuleEnabled() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(loyalRule(true)));
        when(bidRepository.findLoyalSenderIds(travelerId, "Paris", "Dakar"))
                .thenReturn(List.of(senderId1, senderId2));

        listener.onAnnouncementPublished(new AnnouncementPublishedEvent(
                announcementId, travelerId, "Jean", "Paris", "Dakar"));

        verify(notificationDispatcher).notifyUnlessBlocked(
                eq(senderId1), eq(travelerId), any(), any(), any(), eq(false));
        verify(notificationDispatcher).notifyUnlessBlocked(
                eq(senderId2), eq(travelerId), any(), any(), any(), eq(false));
    }

    /**
     * Confidentialité — la règle est armée par le voyageur et parle de son trajet : un
     * expéditeur masqué pour lui ne reçoit rien. Le listener délègue au dispatcher en lui
     * déclarant l'émetteur ; la voie générique, aveugle au blocage, est proscrite ici.
     */
    @Test
    void onAnnouncementPublished_routesThroughBlockAwareChannel() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(loyalRule(true)));
        when(bidRepository.findLoyalSenderIds(travelerId, "Paris", "Dakar"))
                .thenReturn(List.of(senderId1));
        when(notificationDispatcher.notifyUnlessBlocked(
                eq(senderId1), eq(travelerId), any(), any(), any(), eq(false))).thenReturn(false);

        listener.onAnnouncementPublished(new AnnouncementPublishedEvent(
                announcementId, travelerId, "Jean", "Paris", "Dakar"));

        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any(), anyBoolean());
    }

    /**
     * Seule notification déclenchée par un tiers : le destinataire n'a rien demandé, c'est
     * le voyageur qui active la règle, et aucun réglage ne permet de la couper. Elle doit
     * donc rester dans la boîte de réception sans réveiller le téléphone.
     */
    @Test
    void onAnnouncementPublished_neverSendsPushToLoyalSenders() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(loyalRule(true)));
        when(bidRepository.findLoyalSenderIds(travelerId, "Paris", "Dakar"))
                .thenReturn(List.of(senderId1));

        listener.onAnnouncementPublished(new AnnouncementPublishedEvent(
                announcementId, travelerId, "Jean", "Paris", "Dakar"));

        // La surcharge 4 arguments pousse un push : elle ne doit jamais être empruntée ici.
        verify(notificationDispatcher, never()).notifyUnlessBlocked(any(), any(), any(), any(), any());
        verify(notificationDispatcher, never())
                .notifyUnlessBlocked(any(), any(), any(), any(), any(), eq(true));
    }

    @Test
    void onAnnouncementPublished_doesNothingWhenRuleDisabled() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(loyalRule(false)));

        listener.onAnnouncementPublished(new AnnouncementPublishedEvent(
                announcementId, travelerId, "Jean", "Paris", "Dakar"));

        verify(bidRepository, never()).findLoyalSenderIds(any(), any(), any());
        verify(notificationDispatcher, never())
                .notifyUnlessBlocked(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void onAnnouncementPublished_doesNothingWhenNoLoyalSenders() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(loyalRule(true)));
        when(bidRepository.findLoyalSenderIds(travelerId, "Paris", "Dakar"))
                .thenReturn(List.of());

        listener.onAnnouncementPublished(new AnnouncementPublishedEvent(
                announcementId, travelerId, "Jean", "Paris", "Dakar"));

        verify(notificationDispatcher, never())
                .notifyUnlessBlocked(any(), any(), any(), any(), any(), anyBoolean());
    }
}
