package com.yadony.api.cancellation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Procédure « destinataire absent » (FLUTTER-E2).
 *
 * @param minWaitMinutes délai minimal entre l'arrivée déclarée et le signalement. Aucun
 *                       rendez-vous de livraison n'existe dans le modèle : l'arrivée déclarée
 *                       (bids.arrived_at) est le seul repère horodaté côté serveur. 120 min par
 *                       défaut (valeur prudente, à valider par le produit).
 * @param holdDays       durée de garde du colis par le voyageur après le signalement ; au-delà,
 *                       sans livraison ni contestation en cours, le colis passe « non réclamé ».
 */
@ConfigurationProperties(prefix = "yadony.delivery-no-show")
public record DeliveryNoShowProperties(int minWaitMinutes, int holdDays) {

    public DeliveryNoShowProperties {
        if (minWaitMinutes <= 0) minWaitMinutes = 120;
        if (holdDays <= 0) holdDays = 7;
    }
}
