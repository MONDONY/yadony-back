package com.yadony.api.automation;

import com.yadony.api.matching.BidService;
import com.yadony.api.matching.events.BidCreatedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AutomationBidListenerTest {

    @Mock private AutomationRuleRepository ruleRepository;
    @Mock private AutomationActionExecutor executor;
    @Mock private BidService bidService;
    @Mock private UserRepository userRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private BidRepository bidRepository;

    private AutomationBidListener listener;
    private UUID travelerId, bidId, announcementId, senderId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new AutomationBidListener(ruleRepository, executor, bidService,
                userRepository, announcementRepository, notificationDispatcher, bidRepository);
        travelerId = UUID.randomUUID();
        bidId = UUID.randomUUID();
        announcementId = UUID.randomUUID();
        senderId = UUID.randomUUID();
        when(executor.tryExecuteBidAction(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> { ((java.util.function.Supplier<?>) inv.getArgument(4)).get(); return true; });
    }

    private AutomationRuleEntity presetRule(String presetId, boolean enabled, Map<String, Object> action) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setTravelerId(travelerId);
        r.setRuleType("PRESET");
        r.setPresetRuleId(presetId);
        r.setEnabled(enabled);
        r.setAction(action);
        return r;
    }

    @Test
    void onBidCreated_rejectsOverweight_evenIfAcceptTrustedAlsoEnabled() {
        UserEntity sender = new UserEntity();
        sender.setAverageRating(new BigDecimal("4.9"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("5"));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_reject_overweight", true, Map.of()),
                presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("10"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(bidService).rejectBidBySystem(eq(bidId), eq(travelerId), any());
        verify(bidService, never()).acceptBidBySystem(any(), any());
    }

    @Test
    void onBidCreated_acceptsTrustedSenderWhenRatingAboveThreshold() {
        UserEntity sender = new UserEntity();
        sender.setAverageRating(new BigDecimal("4.8"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(bidService).acceptBidBySystem(bidId, travelerId);
    }

    @Test
    void onBidCreated_cashBid_doesNotRunPaymentEscrowAutomation() {
        BidEntity cashBid = new BidEntity();
        cashBid.setPaymentMethod(PaymentMethod.CASH);
        cashBid.setStatus(BidStatus.PENDING);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(cashBid));

        UserEntity sender = new UserEntity();
        sender.setAverageRating(new BigDecimal("4.8"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0))));

        listener.onBidCreated(new BidCreatedEvent(
                bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar"));

        verify(bidService, never()).acceptBidBySystem(any(), any());
        verify(bidService, never()).rejectBidBySystem(any(), any(), any());
    }

    @Test
    void onBidCreated_doesNothingWhenNoRuleMatches() {
        UserEntity sender = new UserEntity();
        sender.setAverageRating(new BigDecimal("3.0"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(bidService, never()).acceptBidBySystem(any(), any());
        verify(bidService, never()).rejectBidBySystem(any(), any(), any());
    }

    @Test
    void onBidCreated_notifiesLastMinuteWhenDepartureWithinConfiguredHours() {
        UserEntity sender = new UserEntity();
        sender.setAverageRating(new BigDecimal("3.0"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        announcement.setDepartureAt(OffsetDateTime.now().plusHours(10));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("alert_last_minute_bid", true, Map.of("hoursBeforeDeparture", 48))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(notificationDispatcher).notifyUser(eq(travelerId), any(), any(), any());
    }

    @Test
    void onBidCreated_doesNotNotifyLastMinuteWhenDepartureFarAway() {
        UserEntity sender = new UserEntity();
        sender.setAverageRating(new BigDecimal("3.0"));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal("20"));
        announcement.setDepartureAt(OffsetDateTime.now().plusDays(10));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("alert_last_minute_bid", true, Map.of("hoursBeforeDeparture", 48))
        ));

        BidCreatedEvent event = new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", new BigDecimal("5"), "Paris → Dakar");
        listener.onBidCreated(event);

        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }

    private AutomationRuleEntity customRule(String name, String actionType, String message,
                                            List<Map<String, Object>> conditions) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setTravelerId(travelerId);
        r.setRuleType("CUSTOM");
        r.setName(name);
        r.setEnabled(true);
        r.setConditions(conditions);
        r.setAction(message == null
                ? Map.of("type", actionType)
                : Map.of("type", actionType, "message", message));
        return r;
    }

    private void stubBid(String contentCategory) {
        BidEntity bid = new BidEntity();
        bid.setContentCategory(contentCategory);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
    }

    private AnnouncementEntity stubAnnouncement(String availableKg) {
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAvailableKg(new BigDecimal(availableKg));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        return announcement;
    }

    private void stubSender(String rating) {
        UserEntity sender = new UserEntity();
        if (rating != null) sender.setAverageRating(new BigDecimal(rating));
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
    }

    private BidCreatedEvent event(String weightKg) {
        return new BidCreatedEvent(bidId, announcementId, travelerId, senderId,
                "Awa", weightKg == null ? null : new BigDecimal(weightKg), "Paris → Dakar");
    }

    @Test
    void customReject_matching_rejectsBidWithCustomMessage() {
        stubAnnouncement("20");
        stubSender("3.0");
        stubBid("Poissons");
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                customRule("Refuser aliments", "auto_reject", "Pas de denrées périssables.",
                        List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons")))));

        listener.onBidCreated(event("8"));

        verify(executor).tryExecuteBidAction(any(), eq(travelerId), eq(bidId),
                eq("CUSTOM_AUTO_REJECT"), any());
        verify(bidService).rejectBidBySystem(bidId, travelerId, "Pas de denrées périssables.");
        verify(bidService, never()).acceptBidBySystem(any(), any());
    }

    @Test
    void customReject_withoutMessage_usesFallbackReasonWithRuleName() {
        stubAnnouncement("20");
        stubSender("3.0");
        stubBid("Poissons");
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                customRule("Refuser aliments", "auto_reject", null,
                        List.of(Map.of("field", "content_type", "operator", "eq", "value", "poissons")))));

        listener.onBidCreated(event("8"));

        verify(bidService).rejectBidBySystem(bidId, travelerId,
                "Refusé automatiquement par une règle du voyageur : Refuser aliments.");
    }

    @Test
    void customReject_beatsPresetAccept_evenIfSenderTrusted() {
        stubAnnouncement("20");
        stubSender("5.0");
        stubBid("Poissons");
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0)),
                customRule("Refuser aliments", "auto_reject", null,
                        List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons")))));

        listener.onBidCreated(event("8"));

        verify(bidService).rejectBidBySystem(eq(bidId), eq(travelerId), any());
        verify(bidService, never()).acceptBidBySystem(any(), any());
    }

    @Test
    void presetReject_blocksCustomAccept() {
        stubAnnouncement("5");
        stubSender("5.0");
        stubBid("Vêtements");
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_reject_overweight", true, Map.of()),
                customRule("Accepter légers", "auto_accept", null,
                        List.of(Map.of("field", "weight_kg", "operator", "gte", "value", "1")))));

        listener.onBidCreated(event("10"));

        verify(bidService).rejectBidBySystem(eq(bidId), eq(travelerId), any());
        verify(bidService, never()).acceptBidBySystem(any(), any());
    }

    @Test
    void rejectMatched_butExecutionBlocked_stillBlocksAccept() {
        // Le plafond quotidien bloque l'exécution du refus (tryExecuteBidAction -> false)
        // mais une règle de refus a MATCHÉ : l'acceptation doit rester bloquée.
        // doReturn (et non when(...).thenReturn) car le stub déjà en place dans setUp()
        // a un thenAnswer avec effet de bord : re-passer par when() ré-exécuterait cet
        // effet de bord avec des arguments matcher (Supplier null) -> NPE.
        doReturn(false).when(executor).tryExecuteBidAction(any(), any(), any(), any(), any());
        stubAnnouncement("20");
        stubSender("5.0");
        stubBid("Poissons");
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                customRule("Refuser aliments", "auto_reject", null,
                        List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons"))),
                customRule("Accepter tout", "auto_accept", null,
                        List.of(Map.of("field", "weight_kg", "operator", "gte", "value", "1")))));

        listener.onBidCreated(event("8"));

        verify(executor).tryExecuteBidAction(any(), eq(travelerId), eq(bidId), eq("CUSTOM_AUTO_REJECT"), any());
        verify(executor, never()).tryExecuteBidAction(any(), any(), any(), eq("CUSTOM_AUTO_ACCEPT"), any());
    }

    @Test
    void twoCustomRejectsMatch_onlyFirstExecuted() {
        stubAnnouncement("20");
        stubSender("3.0");
        stubBid("Poissons");
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                customRule("Règle A", "auto_reject", "Motif A",
                        List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons"))),
                customRule("Règle B", "auto_reject", "Motif B",
                        List.of(Map.of("field", "weight_kg", "operator", "gte", "value", "1")))));

        listener.onBidCreated(event("8"));

        verify(executor, times(1)).tryExecuteBidAction(any(), any(), any(), eq("CUSTOM_AUTO_REJECT"), any());
        verify(bidService).rejectBidBySystem(bidId, travelerId, "Motif A");
    }

    @Test
    void customAccept_matching_acceptsBid() {
        stubAnnouncement("20");
        stubSender("3.0");
        stubBid("Vêtements");
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                customRule("Accepter Dakar", "auto_accept", null,
                        List.of(Map.of("field", "corridor", "operator", "eq", "value", "paris → dakar")))));

        listener.onBidCreated(event("8"));

        verify(executor).tryExecuteBidAction(any(), eq(travelerId), eq(bidId), eq("CUSTOM_AUTO_ACCEPT"), any());
        verify(bidService).acceptBidBySystem(bidId, travelerId);
    }

    @Test
    void customAccept_matching_onSenderRatingCapacityAndHoursBeforeDeparture_wiredCorrectly() {
        // BidEvaluationContext est un record positionnel à 6 arguments dont senderRating et
        // capacityFreeKg sont deux BigDecimal consécutifs : une inversion de ces deux slots
        // au câblage (AutomationBidListener) compilerait sans erreur. On choisit trois valeurs
        // toutes distinctes entre elles pour que le moindre échange de position fasse échouer
        // ce test (eq strict sur sender_rating/capacity_free_kg, gte tolérant sur les heures
        // avant départ pour ne pas être fragile au timing).
        stubSender("4.5");
        AnnouncementEntity announcement = stubAnnouncement("15");
        announcement.setDepartureAt(OffsetDateTime.now().plusHours(30));
        stubBid("Vêtements");
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                customRule("Combo triple", "auto_accept", null, List.of(
                        Map.of("field", "sender_rating", "operator", "eq", "value", "4.5"),
                        Map.of("field", "capacity_free_kg", "operator", "eq", "value", "15"),
                        Map.of("field", "hours_before_departure", "operator", "gte", "value", "10")))));

        listener.onBidCreated(event("8"));

        verify(executor).tryExecuteBidAction(any(), eq(travelerId), eq(bidId), eq("CUSTOM_AUTO_ACCEPT"), any());
        verify(bidService).acceptBidBySystem(bidId, travelerId);
    }

    @Test
    void twoCustomAcceptsMatch_onlyFirstExecuted() {
        // Symétrique de twoCustomRejectsMatch_onlyFirstExecuted, côté auto_accept : deux
        // règles custom qui matchent toutes les deux, seule la première dans l'ordre
        // (createdAt croissant, tel que retourné par le repository) est exécutée.
        stubAnnouncement("20");
        stubSender("3.0");
        stubBid("Vêtements");
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                customRule("Règle A", "auto_accept", null,
                        List.of(Map.of("field", "corridor", "operator", "eq", "value", "Paris → Dakar"))),
                customRule("Règle B", "auto_accept", null,
                        List.of(Map.of("field", "weight_kg", "operator", "gte", "value", "1")))));

        listener.onBidCreated(event("8"));

        verify(executor, times(1)).tryExecuteBidAction(any(), any(), any(), eq("CUSTOM_AUTO_ACCEPT"), any());
        verify(bidService).acceptBidBySystem(bidId, travelerId);
    }

    @Test
    void presetAcceptMatched_customAcceptSkipped() {
        stubAnnouncement("20");
        stubSender("4.8");
        stubBid("Vêtements");
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                presetRule("auto_accept_trusted", true, Map.of("minRating", 4.0)),
                customRule("Accepter tout", "auto_accept", null,
                        List.of(Map.of("field", "weight_kg", "operator", "gte", "value", "1")))));

        listener.onBidCreated(event("8"));

        verify(executor, times(1)).tryExecuteBidAction(any(), any(), any(), any(), any());
        verify(executor).tryExecuteBidAction(any(), eq(travelerId), eq(bidId), eq("AUTO_ACCEPT_TRUSTED"), any());
    }

    @Test
    void customRule_unsupportedActionType_ignored() {
        stubAnnouncement("20");
        stubSender("3.0");
        stubBid("Poissons");
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                customRule("Alerte aliments", "send_alert", null,
                        List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons")))));

        listener.onBidCreated(event("8"));

        verify(executor, never()).tryExecuteBidAction(any(), any(), any(), any(), any());
        verifyNoInteractions(bidService);
    }

    @Test
    void customRule_disabled_ignored() {
        stubAnnouncement("20");
        stubSender("3.0");
        stubBid("Poissons");
        AutomationRuleEntity disabled = customRule("Refuser aliments", "auto_reject", null,
                List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons")));
        disabled.setEnabled(false);
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(disabled));

        listener.onBidCreated(event("8"));

        verifyNoInteractions(bidService);
    }

    @Test
    void customRuleEvaluationThrows_doesNotEscapeListener_noAction() {
        // onBidCreated est un @TransactionalEventListener(AFTER_COMMIT) synchrone, appelé
        // depuis PaymentService.promoteBidOnPaymentAuthorized (webhook Stripe / confirmation
        // de paiement). Toute exception qui s'en échapperait remonterait en HTTP 500 pour
        // l'expéditeur. Une règle custom dont l'évaluation lève (donnée corrompue au-delà de
        // ce que le null-guard de l'évaluateur couvre) ne doit donc jamais faire planter le
        // listener (FIX 2b) : ici on force une Map de condition qui lève au premier get().
        stubAnnouncement("20");
        stubSender("3.0");
        stubBid("Poissons");
        @SuppressWarnings("unchecked")
        Map<String, Object> throwingCondition = mock(Map.class);
        when(throwingCondition.get(any())).thenThrow(new RuntimeException("donnée corrompue"));
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                customRule("Règle cassée", "auto_reject", null, List.of(throwingCondition))));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> listener.onBidCreated(event("8")));

        verifyNoInteractions(bidService);
    }

    @Test
    void bidNotFound_customRulesSkipped_noAction() {
        stubAnnouncement("20");
        stubSender("3.0");
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                customRule("Refuser aliments", "auto_reject", null,
                        List.of(Map.of("field", "content_type", "operator", "eq", "value", "Poissons")))));

        listener.onBidCreated(event("8"));

        verifyNoInteractions(bidService);
    }
}
