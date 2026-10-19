package com.yadony.api.payments.supplement;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * {@code yadony.bid-supplement.*} (décisions du propriétaire du 19/10/2026) : net de 1 à 300 €
 * d'équivalent, 2 demandes au plus par colis, 2 h pour répondre, plus de demande dans les 15 min
 * avant le départ. Éteint par défaut ({@code BID_SUPPLEMENT_ENABLED}) : les apps installées ne
 * savent pas afficher un supplément.
 */
@ConfigurationProperties(prefix = "yadony.bid-supplement")
public class BidSupplementProperties {

    private boolean enabled = false;
    /** Délai de réponse de l'expéditeur, raccourci pour finir {@link #departureMargin} avant le départ. */
    private Duration expiry = Duration.ofHours(2);
    /** Plus aucune demande ouverte dans cette marge avant le départ. */
    private Duration departureMargin = Duration.ofMinutes(15);
    /** Une demande qui laisserait moins que ce délai pour répondre est refusée. */
    private Duration minResponseWindow = Duration.ofMinutes(10);
    private int maxPerBid = 2;
    /** Bornes du net en euros, converties dans la devise du colis ({@code CurrencyBounds.scaleFromEur}). */
    private BigDecimal minNetEur = BigDecimal.ONE;
    private BigDecimal maxNetEur = new BigDecimal("300");
    /** Intervalle du filet planifié (expiration, colis terminé). */
    private Duration reconcileInterval = Duration.ofMinutes(10);
    /** Filet planifié actif ; coupé dans les tests (une base partagée, des horloges réelles). */
    private boolean reconcileEnabled = true;
    /**
     * Rail mobile money : nombre d'opérations pawaPay mortes (échouées ou refusées) d'un même type
     * (versement, remboursement) au-delà duquel le filet cesse de resoumettre ; une alerte
     * « intervention manuelle » prend le relais.
     */
    private int mmMaxAttempts = 3;
    /**
     * Rail espèces : délai laissé au voyageur pour confirmer la 3DS de la commission du supplément ;
     * au-delà, la demande ({@code COMMISSION_PENDING}) est annulée par le filet.
     */
    private Duration commissionConfirmWindow = Duration.ofMinutes(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getExpiry() { return expiry; }
    public void setExpiry(Duration expiry) { this.expiry = expiry; }
    public Duration getDepartureMargin() { return departureMargin; }
    public void setDepartureMargin(Duration departureMargin) { this.departureMargin = departureMargin; }
    public Duration getMinResponseWindow() { return minResponseWindow; }
    public void setMinResponseWindow(Duration minResponseWindow) { this.minResponseWindow = minResponseWindow; }
    public int getMaxPerBid() { return maxPerBid; }
    public void setMaxPerBid(int maxPerBid) { this.maxPerBid = maxPerBid; }
    public BigDecimal getMinNetEur() { return minNetEur; }
    public void setMinNetEur(BigDecimal minNetEur) { this.minNetEur = minNetEur; }
    public BigDecimal getMaxNetEur() { return maxNetEur; }
    public void setMaxNetEur(BigDecimal maxNetEur) { this.maxNetEur = maxNetEur; }
    public Duration getReconcileInterval() { return reconcileInterval; }
    public void setReconcileInterval(Duration reconcileInterval) { this.reconcileInterval = reconcileInterval; }
    public boolean isReconcileEnabled() { return reconcileEnabled; }
    public int getMmMaxAttempts() { return mmMaxAttempts; }
    public void setMmMaxAttempts(int mmMaxAttempts) { this.mmMaxAttempts = mmMaxAttempts; }
    public void setReconcileEnabled(boolean reconcileEnabled) { this.reconcileEnabled = reconcileEnabled; }
    public Duration getCommissionConfirmWindow() { return commissionConfirmWindow; }
    public void setCommissionConfirmWindow(Duration commissionConfirmWindow) { this.commissionConfirmWindow = commissionConfirmWindow; }
}
