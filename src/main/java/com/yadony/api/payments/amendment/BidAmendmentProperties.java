package com.yadony.api.payments.amendment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * {@code yadony.bid-amendment.*} : modification d'un colis accepté (kilos ou articles de grille ajoutés
 * par l'expéditeur). Éteint par défaut ({@code BID_AMENDMENT_ENABLED}) : les apps installées ne savent pas
 * l'afficher. Détecté par {@code @ConfigurationPropertiesScan}.
 */
@ConfigurationProperties(prefix = "yadony.bid-amendment")
public class BidAmendmentProperties {

    private boolean enabled = false;
    /** Délai de réponse du voyageur, plafonné à {@link #departureMargin} avant le départ et à la date limite de remise. */
    private Duration expiry = Duration.ofHours(48);
    /** Plus aucune demande dans cette marge avant le départ. */
    private Duration departureMargin = Duration.ofMinutes(15);
    /** Une demande qui laisserait moins que ce délai pour répondre est refusée. */
    private Duration minResponseWindow = Duration.ofHours(2);
    /** Rail carte : délai laissé à l'expéditeur pour confirmer l'autorisation (3DS) avant l'abandon. */
    private Duration authorizationWindow = Duration.ofMinutes(30);
    /** Rail espèces : délai laissé au voyageur pour confirmer la 3DS de la commission. */
    private Duration commissionConfirmWindow = Duration.ofMinutes(30);
    /** Rappel envoyé au voyageur à cette durée avant l'échéance. */
    private Duration reminderBefore = Duration.ofHours(6);
    /** Modifications acceptées au plus par colis. */
    private int maxAcceptedPerBid = 2;
    /** Bornes du net en euros, converties dans la devise du colis ({@code CurrencyBounds.scaleFromEur}). */
    private BigDecimal minNetEur = BigDecimal.ONE;
    private BigDecimal maxNetEur = new BigDecimal("300");
    /** Intervalle du filet planifié (expiration, rappel, colis terminé). */
    private Duration reconcileInterval = Duration.ofMinutes(10);
    /**
     * Rail mobile money : nombre d'opérations pawaPay mortes (échouées ou refusées) d'un même type
     * (versement, remboursement) au-delà duquel le filet cesse de resoumettre ; une alerte
     * « intervention manuelle » prend le relais.
     */
    private int mmMaxAttempts = 3;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getExpiry() { return expiry; }
    public void setExpiry(Duration expiry) { this.expiry = expiry; }
    public Duration getDepartureMargin() { return departureMargin; }
    public void setDepartureMargin(Duration departureMargin) { this.departureMargin = departureMargin; }
    public Duration getMinResponseWindow() { return minResponseWindow; }
    public void setMinResponseWindow(Duration minResponseWindow) { this.minResponseWindow = minResponseWindow; }
    public Duration getAuthorizationWindow() { return authorizationWindow; }
    public void setAuthorizationWindow(Duration authorizationWindow) { this.authorizationWindow = authorizationWindow; }
    public Duration getCommissionConfirmWindow() { return commissionConfirmWindow; }
    public void setCommissionConfirmWindow(Duration commissionConfirmWindow) { this.commissionConfirmWindow = commissionConfirmWindow; }
    public Duration getReminderBefore() { return reminderBefore; }
    public void setReminderBefore(Duration reminderBefore) { this.reminderBefore = reminderBefore; }
    public int getMaxAcceptedPerBid() { return maxAcceptedPerBid; }
    public void setMaxAcceptedPerBid(int maxAcceptedPerBid) { this.maxAcceptedPerBid = maxAcceptedPerBid; }
    public BigDecimal getMinNetEur() { return minNetEur; }
    public void setMinNetEur(BigDecimal minNetEur) { this.minNetEur = minNetEur; }
    public BigDecimal getMaxNetEur() { return maxNetEur; }
    public void setMaxNetEur(BigDecimal maxNetEur) { this.maxNetEur = maxNetEur; }
    public Duration getReconcileInterval() { return reconcileInterval; }
    public void setReconcileInterval(Duration reconcileInterval) { this.reconcileInterval = reconcileInterval; }
    public int getMmMaxAttempts() { return mmMaxAttempts; }
    public void setMmMaxAttempts(int mmMaxAttempts) { this.mmMaxAttempts = mmMaxAttempts; }
}
