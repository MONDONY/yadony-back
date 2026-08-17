package com.yadony.api.requests;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Réglages de l'attente de commission d'un accord en espèces.
 *
 * <p>Les {@code @DefaultValue} ne sont pas décoratifs : sans eux, une clé absente
 * de la configuration donne 0 pour un {@code int}, soit une échéance immédiate
 * (toute négociation expirerait à la seconde) et un balayage de remboursement qui
 * ne trouverait plus jamais rien.
 *
 * @param commissionWindowMinutes délai laissé au voyageur pour régler la commission
 *        avant que le fil expire et libère la demande à d'autres voyageurs.
 * @param commissionRefundSweepDays profondeur d'historique du balayage de
 *        rattrapage des commissions débitées non remboursées. Au-delà, un fil
 *        perdant n'est plus relu chez Stripe : une 3DS abandonnée laisse un
 *        PaymentIntent qui n'atteint jamais {@code succeeded}, donc un fil qui
 *        resterait éligible à vie et ferait grossir le balayage avec l'historique
 *        de la plateforme.
 */
@ConfigurationProperties(prefix = "yadony.negotiation")
public record NegotiationProperties(
    @DefaultValue("120") int commissionWindowMinutes,
    @DefaultValue("7") int commissionRefundSweepDays
) {}
