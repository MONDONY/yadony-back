package com.yadony.api.automation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.events.BidCreatedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Écoute BidCreatedEvent (publié une fois le paiement de l'expéditeur autorisé,
 * bid en PAYMENT_ESCROWED) et exécute les règles d'automatisation actives du
 * voyageur propriétaire de l'annonce : refus auto (priorité), acceptation auto,
 * alerte dernière minute.
 *
 * <p><b>Volontairement PAS de {@code @Transactional} sur cette classe/méthode.</b>
 * {@link #onBidCreated} appelle {@link AutomationActionExecutor#tryExecuteBidAction}
 * qui, elle-même, invoque {@code BidService.acceptBidBySystem}/{@code rejectBidBySystem}
 * (chacune {@code @Transactional}). Ajouter {@code @Transactional} ici engloberait ces
 * appels dans une transaction physique partagée avec cette méthode : une exception levée
 * par l'action marquerait la transaction globale rollback-only même si
 * {@code tryExecuteBidAction} l'attrape en interne, et l'écriture d'historique
 * "FAILURE"/le commit final échoueraient avec {@code UnexpectedRollbackException} — le
 * même problème que celui corrigé en Task 2 sur {@code AutomationActionExecutor}. Ne pas
 * réintroduire {@code @Transactional} ici.
 */
@Component
public class AutomationBidListener {

    private static final Logger log = LoggerFactory.getLogger(AutomationBidListener.class);

    private final AutomationRuleRepository ruleRepository;
    private final AutomationActionExecutor executor;
    private final BidService bidService;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final BidRepository bidRepository;

    public AutomationBidListener(AutomationRuleRepository ruleRepository,
                                 AutomationActionExecutor executor,
                                 BidService bidService,
                                 UserRepository userRepository,
                                 AnnouncementRepository announcementRepository,
                                 NotificationDispatcher notificationDispatcher,
                                 BidRepository bidRepository) {
        this.ruleRepository = ruleRepository;
        this.executor = executor;
        this.bidService = bidService;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.bidRepository = bidRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBidCreated(BidCreatedEvent event) {
        BidEntity persistedBid = bidRepository.findById(event.getBidId()).orElse(null);
        if (persistedBid != null && persistedBid.getPaymentMethod() == PaymentMethod.CASH) {
            log.warn("Automation: CASH bid {} ignored because it is not in escrow", event.getBidId());
            return;
        }

        List<AutomationRuleEntity> rules =
                ruleRepository.findByTravelerIdOrderByCreatedAtAsc(event.getTravelerId());

        Optional<AutomationRuleEntity> rejectRule = findEnabledPreset(rules, "auto_reject_overweight");
        Optional<AutomationRuleEntity> acceptRule = findEnabledPreset(rules, "auto_accept_trusted");
        Optional<AutomationRuleEntity> lastMinuteRule = findEnabledPreset(rules, "alert_last_minute_bid");
        List<AutomationRuleEntity> customRejectRules = findEnabledCustom(rules, "auto_reject");
        List<AutomationRuleEntity> customAcceptRules = findEnabledCustom(rules, "auto_accept");

        AnnouncementEntity announcement = announcementRepository.findById(event.getAnnouncementId())
                .orElse(null);
        if (announcement == null) {
            log.warn("Automation: announcement {} not found for bid {}", event.getAnnouncementId(), event.getBidId());
            return;
        }

        UserEntity sender = null;
        if (acceptRule.isPresent() || !customRejectRules.isEmpty() || !customAcceptRules.isEmpty()) {
            sender = userRepository.findById(event.getSenderId()).orElse(null);
        }

        BidEvaluationContext ctx = null;
        if (!customRejectRules.isEmpty() || !customAcceptRules.isEmpty()) {
            if (persistedBid == null) {
                log.warn("Automation: bid {} not found, custom rules skipped", event.getBidId());
            } else {
                ctx = new BidEvaluationContext(
                        event.getWeightKg(),
                        event.getCorridor(),
                        persistedBid.getContentCategory(),
                        sender != null ? sender.getAverageRating() : null,
                        announcement.getAvailableKg(),
                        announcement.getDepartureAt() == null ? null
                                : Duration.between(OffsetDateTime.now(), announcement.getDepartureAt()).toHours());
            }
        }

        // Phase refus. Toute règle de refus qui MATCHE bloque la phase acceptation,
        // même si son exécution est bloquée (plafond) ou échoue : un colis visé par
        // un refus ne doit jamais être auto-accepté par une autre règle.
        boolean rejectMatched = false;
        if (rejectRule.isPresent() && event.getWeightKg() != null
                && event.getWeightKg().compareTo(announcement.getAvailableKg()) > 0) {
            rejectMatched = true;
            executor.tryExecuteBidAction(rejectRule.get(), event.getTravelerId(), event.getBidId(),
                    "AUTO_REJECT_OVERWEIGHT", () -> {
                        bidService.rejectBidBySystem(event.getBidId(), event.getTravelerId(),
                                "Le poids de ce colis dépasse la capacité restante sur ce trajet.");
                        return null;
                    });
        }
        if (!rejectMatched && ctx != null) {
            for (AutomationRuleEntity rule : customRejectRules) {
                if (safeMatches(rule, ctx)) {
                    rejectMatched = true;
                    String reason = customRejectReason(rule);
                    executor.tryExecuteBidAction(rule, event.getTravelerId(), event.getBidId(),
                            "CUSTOM_AUTO_REJECT", () -> {
                                bidService.rejectBidBySystem(event.getBidId(), event.getTravelerId(), reason);
                                return null;
                            });
                    break;
                }
            }
        }

        // Phase acceptation — une seule action bid par bid (accept XOR reject).
        boolean acceptMatched = false;
        if (!rejectMatched && acceptRule.isPresent()) {
            AutomationRuleEntity rule = acceptRule.get();
            BigDecimal minRating = configNumber(rule, "minRating", new BigDecimal("4.0"));
            boolean weightOk = event.getWeightKg() == null
                    || event.getWeightKg().compareTo(announcement.getAvailableKg()) <= 0;
            boolean ratingOk = sender != null && sender.getAverageRating() != null
                    && sender.getAverageRating().compareTo(minRating) >= 0;
            if (weightOk && ratingOk) {
                acceptMatched = true;
                executor.tryExecuteBidAction(rule, event.getTravelerId(), event.getBidId(),
                        "AUTO_ACCEPT_TRUSTED", () -> {
                            bidService.acceptBidBySystem(event.getBidId(), event.getTravelerId());
                            return null;
                        });
            }
        }
        if (!rejectMatched && !acceptMatched && ctx != null) {
            for (AutomationRuleEntity rule : customAcceptRules) {
                if (safeMatches(rule, ctx)) {
                    executor.tryExecuteBidAction(rule, event.getTravelerId(), event.getBidId(),
                            "CUSTOM_AUTO_ACCEPT", () -> {
                                bidService.acceptBidBySystem(event.getBidId(), event.getTravelerId());
                                return null;
                            });
                    break;
                }
            }
        }

        if (lastMinuteRule.isPresent() && announcement.getDepartureAt() != null) {
            AutomationRuleEntity rule = lastMinuteRule.get();
            int hoursBeforeDeparture = configInt(rule, "hoursBeforeDeparture", 48);
            long hoursUntilDeparture = Duration.between(
                    OffsetDateTime.now(), announcement.getDepartureAt()).toHours();
            if (hoursUntilDeparture >= 0 && hoursUntilDeparture < hoursBeforeDeparture) {
                notificationDispatcher.notifyUser(event.getTravelerId(),
                        "Offre de dernière minute",
                        "Une offre vient d'arriver pour un départ dans moins de "
                                + hoursBeforeDeparture + "h (" + event.getCorridor() + ").",
                        Map.of("type", "automation_last_minute", "bidId", event.getBidId().toString()));
                executor.recordNotification(rule, event.getTravelerId(), "ALERT_LAST_MINUTE_BID");
            }
        }
    }

    /**
     * Enveloppe {@link CustomRuleConditionEvaluator#matches} pour qu'AUCUNE exception
     * d'évaluation d'une règle custom (données malformées au-delà de ce que couvre le
     * fail-safe interne de l'évaluateur) ne puisse s'échapper de {@link #onBidCreated}.
     *
     * <p>Important : ce garde-fou entoure UNIQUEMENT l'évaluation/le matching — jamais les
     * appels à {@code executor.tryExecuteBidAction}, qui gère déjà ses propres échecs
     * (try/catch interne + écriture d'historique FAILURE) et ne doit pas être court-circuité
     * ici. {@code onBidCreated} est un {@code @TransactionalEventListener(AFTER_COMMIT)}
     * synchrone, invoqué depuis {@code PaymentService.promoteBidOnPaymentAuthorized} — donc
     * depuis le webhook Stripe et le chemin de confirmation de paiement. Une exception qui
     * s'échapperait jusqu'à l'appelant se traduirait par un HTTP 500 pour l'expéditeur à
     * chaque bid, tant que la règle malformée existe.
     */
    private static boolean safeMatches(AutomationRuleEntity rule, BidEvaluationContext ctx) {
        try {
            return CustomRuleConditionEvaluator.matches(rule, ctx);
        } catch (RuntimeException e) {
            log.warn("Automation custom rule {}: évaluation en erreur, règle ignorée (fail-safe) : {}",
                    rule.getId(), e.getMessage(), e);
            return false;
        }
    }

    private Optional<AutomationRuleEntity> findEnabledPreset(List<AutomationRuleEntity> rules, String presetId) {
        return rules.stream()
                .filter(r -> presetId.equals(r.getPresetRuleId()) && r.isEnabled())
                .findFirst();
    }

    private List<AutomationRuleEntity> findEnabledCustom(List<AutomationRuleEntity> rules, String actionType) {
        return rules.stream()
                .filter(r -> "CUSTOM".equals(r.getRuleType()) && r.isEnabled())
                .filter(r -> r.getAction() != null && actionType.equals(r.getAction().get("type")))
                .toList();
    }

    private String customRejectReason(AutomationRuleEntity rule) {
        Object message = rule.getAction() != null ? rule.getAction().get("message") : null;
        if (message != null && !message.toString().isBlank()) {
            return message.toString();
        }
        return "Refusé automatiquement par une règle du voyageur : " + rule.getName() + ".";
    }

    private BigDecimal configNumber(AutomationRuleEntity rule, String key, BigDecimal fallback) {
        Object v = rule.getAction() != null ? rule.getAction().get(key) : null;
        if (v == null) return fallback;
        return new BigDecimal(v.toString());
    }

    private int configInt(AutomationRuleEntity rule, String key, int fallback) {
        Object v = rule.getAction() != null ? rule.getAction().get(key) : null;
        if (v == null) return fallback;
        return Integer.parseInt(v.toString());
    }
}
