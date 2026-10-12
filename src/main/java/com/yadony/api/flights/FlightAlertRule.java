package com.yadony.api.flights;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Règle d'envoi d'une alerte prix, isolée pour être testée seule.
 *
 * <ul>
 *   <li>Prix au-dessus du seuil : rien n'est envoyé, et l'anti-spam est remis à zéro pour
 *       qu'une prochaine baisse sous le seuil soit annoncée.</li>
 *   <li>Prix sous le seuil, jamais annoncé : notification.</li>
 *   <li>Prix sous le seuil, déjà annoncé : notification seulement s'il baisse encore.</li>
 * </ul>
 * Idempotente : rejouer la même vérification avec le même prix ne renvoie rien.
 */
final class FlightAlertRule {

    private FlightAlertRule() {
    }

    /** Met l'alerte à jour et dit s'il faut prévenir l'utilisateur. */
    static boolean apply(FlightPriceAlertEntity alert, BigDecimal cheapest, LocalDateTime now) {
        alert.setLastCheckedAt(now);
        if (cheapest == null) {
            return false;
        }
        alert.setLastSeenPrice(cheapest);
        if (cheapest.compareTo(alert.getMaxPrice()) > 0) {
            alert.setLastNotifiedPrice(null);
            return false;
        }
        BigDecimal lastNotified = alert.getLastNotifiedPrice();
        if (lastNotified != null && cheapest.compareTo(lastNotified) >= 0) {
            return false;
        }
        alert.setLastNotifiedPrice(cheapest);
        alert.setLastNotifiedAt(now);
        return true;
    }
}
