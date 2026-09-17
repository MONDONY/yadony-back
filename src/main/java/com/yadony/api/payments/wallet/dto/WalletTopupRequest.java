package com.yadony.api.payments.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class WalletTopupRequest {

    @NotNull
    @DecimalMin(value = "1.00", message = "Le montant minimum est 1 €")
    private BigDecimal amount;

    @NotNull
    private String paymentMethod; // STRIPE | WAVE | ORANGE_MONEY

    /**
     * IGNORÉ depuis le correctif « devise de recharge » : la devise créditée est
     * résolue côté serveur ({@code ActiveCurrencyResolver}), la même que celle
     * relue par les contrôles de solde. Laisser le client la choisir permettait
     * de créditer une devise et d'en contrôler une autre — le portefeuille
     * restait alors éternellement « insuffisant ».
     *
     * <p>Champ conservé pour ne pas rejeter les clients déjà déployés qui
     * l'envoient encore ; à supprimer quand ils auront tous été mis à jour.
     */
    @Deprecated
    private String currencyCode;

    /**
     * Numéro mobile money qui paie la recharge (E.164), obligatoire pour
     * {@code paymentMethod = MOBILE_MONEY}, ignoré pour les autres rails.
     */
    private String phoneNumber;

    /**
     * Code pawaPay du réseau choisi ({@code ORANGE_CIV}, {@code WAVE_SEN}…), facultatif :
     * sans lui, l'opérateur prédit par pawaPay pour ce numéro s'applique.
     */
    private String provider;

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
}
