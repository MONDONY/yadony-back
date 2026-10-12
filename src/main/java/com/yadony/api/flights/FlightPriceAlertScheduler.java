package com.yadony.api.flights;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vérifie les alertes prix toutes les 6 heures.
 *
 * <p>Les alertes identiques (même trajet, mêmes mois, même devise) partagent un seul appel
 * à Travelpayouts. Aucune transaction n'enveloppe l'exécution : chaque alerte est
 * enregistrée par son propre {@code save}, si bien qu'aucune connexion JDBC n'est tenue
 * pendant les appels réseau, et qu'une erreur sur une alerte n'annule pas les autres.
 *
 * <p>Idempotent : la décision d'envoi ({@link FlightAlertRule}) dépend de l'état enregistré,
 * rejouer une exécution avec les mêmes prix n'envoie rien de plus.
 */
@Component
public class FlightPriceAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(FlightPriceAlertScheduler.class);

    private final FlightPriceAlertRepository alerts;
    private final FlightPriceService prices;
    private final NotificationDispatcher notifications;
    private final boolean enabled;
    private final Clock clock;

    @Autowired
    public FlightPriceAlertScheduler(FlightPriceAlertRepository alerts, FlightPriceService prices,
                                     NotificationDispatcher notifications,
                                     @Value("${yadony.flights.alerts.enabled:true}") boolean enabled) {
        this(alerts, prices, notifications, enabled, Clock.systemUTC());
    }

    FlightPriceAlertScheduler(FlightPriceAlertRepository alerts, FlightPriceService prices,
                              NotificationDispatcher notifications, boolean enabled, Clock clock) {
        this.alerts = alerts;
        this.prices = prices;
        this.notifications = notifications;
        this.enabled = enabled;
        this.clock = clock;
    }

    @Scheduled(cron = "${yadony.flights.alerts.cron:0 7 */6 * * *}", zone = "UTC")
    public void run() {
        if (!enabled || !prices.isAvailable()) return;
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);

        Map<String, List<FlightPriceAlertEntity>> byQuery = new LinkedHashMap<>();
        for (FlightPriceAlertEntity alert : alerts.findAllByActiveTrue()) {
            if (isExpired(alert, today)) {
                // Période de départ passée : l'alerte n'a plus d'objet, on la met en pause.
                alert.setActive(false);
                saveQuietly(alert);
                continue;
            }
            byQuery.computeIfAbsent(groupKey(alert), k -> new java.util.ArrayList<>()).add(alert);
        }

        int notified = 0;
        for (List<FlightPriceAlertEntity> group : byQuery.values()) {
            BigDecimal cheapest;
            try {
                cheapest = cheapest(group.get(0));
            } catch (FlightProviderException e) {
                // On réessaiera à la prochaine exécution ; l'état des alertes n'est pas touché.
                continue;
            }
            for (FlightPriceAlertEntity alert : group) {
                boolean notify = FlightAlertRule.apply(alert, cheapest, now);
                if (!saveQuietly(alert)) continue;
                if (notify) {
                    send(alert, cheapest);
                    notified++;
                }
            }
        }
        if (notified > 0) log.info("[FlightPriceAlert] {} alerte(s) envoyée(s)", notified);
    }

    /** Prix le plus bas dans la période de l'alerte, {@code null} si aucune offre n'y correspond. */
    private BigDecimal cheapest(FlightPriceAlertEntity alert) {
        FlightAlertWindow window = alert.window();
        BigDecimal cheapest = null;
        for (FlightQuery query : window.queries(alert.getOriginCode(), alert.getDestinationCode(),
                alert.isDirectOnly(), alert.getCurrency())) {
            for (TravelpayoutsPrice price : prices.prices(query)) {
                if (window.accepts(price) && (cheapest == null || price.price().compareTo(cheapest) < 0)) {
                    cheapest = price.price();
                }
            }
        }
        return cheapest;
    }

    /** Deux alertes de même trajet, période, filtre et devise partagent un seul passage. */
    private static String groupKey(FlightPriceAlertEntity alert) {
        return alert.getOriginCode() + "-" + alert.getDestinationCode() + "|" + alert.window().key()
                + "|" + alert.isDirectOnly() + "|" + alert.getCurrency();
    }

    static boolean isExpired(FlightPriceAlertEntity alert, LocalDate today) {
        return alert.getDepartureTo() != null && alert.getDepartureTo().isBefore(today);
    }

    private boolean saveQuietly(FlightPriceAlertEntity alert) {
        try {
            alerts.save(alert);
            return true;
        } catch (RuntimeException e) {
            log.error("[FlightPriceAlert] alerte {} non enregistrée : {}", alert.getId(), e.getMessage());
            return false;
        }
    }

    private void send(FlightPriceAlertEntity alert, BigDecimal cheapest) {
        try {
            Messages m = notifications.messagesFor(alert.getOwnerId());
            String route = label(alert.getOriginLabel(), alert.getOriginCode()) + " → "
                    + label(alert.getDestinationLabel(), alert.getDestinationCode());
            String price = formatPrice(cheapest, alert.getCurrency());
            String threshold = formatPrice(alert.getMaxPrice(), alert.getCurrency());
            String title = m.get("notification.flight-price-alert.title", route);
            String body = m.get("notification.flight-price-alert.body", route, price, threshold);
            Map<String, String> data = Map.of(
                    "type", "FLIGHT_PRICE_ALERT",
                    "alertId", alert.getId().toString(),
                    "origin", alert.getOriginCode(),
                    "destination", alert.getDestinationCode());
            notifications.notifyUser(alert.getOwnerId(), title, body, data);
        } catch (RuntimeException e) {
            log.error("[FlightPriceAlert] notification non envoyée pour l'alerte {} : {}",
                    alert.getId(), e.getMessage());
        }
    }

    private static String label(String label, String code) {
        return label == null || label.isBlank() ? code : label;
    }

    /** « 327 € », « 250000 XOF » : pas de décimales inutiles, symbole pour l'euro seulement. */
    static String formatPrice(BigDecimal amount, String currency) {
        BigDecimal rounded = amount.setScale(0, RoundingMode.HALF_UP);
        return "EUR".equals(currency) ? rounded.toPlainString() + " €" : rounded.toPlainString() + " " + currency;
    }
}
