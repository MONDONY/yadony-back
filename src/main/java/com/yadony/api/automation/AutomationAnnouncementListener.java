package com.yadony.api.automation;

import com.yadony.api.matching.AnnouncementPublishedEvent;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.notifications.NotificationDispatcher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Écoute AnnouncementPublishedEvent : si le voyageur a activé "notify_loyal_senders",
 * notifie chaque expéditeur ayant déjà eu un bid ACCEPTED avec lui sur ce corridor.
 *
 * <p><b>Notification in-app uniquement, jamais de push.</b> C'est la seule notification
 * déclenchée par un tiers : le destinataire n'a rien demandé, c'est le voyageur qui active
 * la règle. Un push réveillerait le téléphone d'un expéditeur qui n'a souscrit à rien et ne
 * dispose d'aucun réglage pour le couper — contrairement aux alertes corridor
 * ({@code pushCorridorAlerts}) et aux abonnements voyageur ({@code isPushEnabled} par
 * abonnement), qui reposent tous deux sur un opt-in explicite. La notification reste
 * persistée et visible dans la boîte de réception, donc la règle du voyageur garde son
 * effet sans imposer d'interruption.
 *
 * <p>Volontairement PAS de {@code @Transactional} supplémentaire sur cette classe/méthode,
 * par cohérence avec {@link AutomationBidListener} : cette méthode n'appelle que
 * {@code notificationDispatcher.notifyUser} et {@code executor.recordNotification} (jamais
 * {@code BidService.acceptBidBySystem}/{@code rejectBidBySystem}), donc il n'y a pas ici le
 * risque de transaction imbriquée documenté sur {@link AutomationBidListener} — mais on
 * garde le même pattern d'écoute par cohérence et pour éviter tout écrit avant le commit de
 * la publication de l'annonce.
 */
@Component
public class AutomationAnnouncementListener {

    private final AutomationRuleRepository ruleRepository;
    private final BidRepository bidRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final AutomationActionExecutor executor;

    public AutomationAnnouncementListener(AutomationRuleRepository ruleRepository,
                                          BidRepository bidRepository,
                                          NotificationDispatcher notificationDispatcher,
                                          AutomationActionExecutor executor) {
        this.ruleRepository = ruleRepository;
        this.bidRepository = bidRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnnouncementPublished(AnnouncementPublishedEvent event) {
        AutomationRuleEntity rule = ruleRepository
                .findByTravelerIdOrderByCreatedAtAsc(event.travelerId())
                .stream()
                .filter(r -> "notify_loyal_senders".equals(r.getPresetRuleId()) && r.isEnabled())
                .findFirst()
                .orElse(null);
        if (rule == null) return;

        List<UUID> loyalSenderIds = bidRepository.findLoyalSenderIds(
                event.travelerId(), event.departureCity(), event.arrivalCity());
        if (loyalSenderIds.isEmpty()) return;

        String corridor = event.departureCity() + " → " + event.arrivalCity();
        for (UUID senderId : loyalSenderIds) {
            // Confidentialité — la règle est armée par le voyageur et parle de SON trajet :
            // un expéditeur masqué pour lui (ou pour qui il l'est) ne reçoit rien. Les
            // transactions passées qui font la « fidélité » sont terminées, elles ne
            // relèvent donc pas de l'exception « transaction en cours ».
            notificationDispatcher.notifyUnlessBlocked(senderId, event.travelerId(),
                    "Nouveau trajet sur votre corridor habituel",
                    event.travelerName() + " vient de publier un nouveau trajet " + corridor + ".",
                    Map.of("type", "automation_loyal_sender", "announcementId", event.announcementId().toString()),
                    false); // in-app seulement : voir le javadoc de classe
        }
        executor.recordNotification(rule, event.travelerId(), "NOTIFY_LOYAL_SENDERS");
    }
}
