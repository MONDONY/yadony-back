package com.yadony.api.payments.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class PaymentResponse {

    private UUID id;
    private UUID bidId;
    private String clientSecret;
    private BigDecimal amount;
    private BigDecimal commissionAmount;
    private String currency = "eur";
    private String status;
    private String stripePaymentIntentId;
    // Types du PaymentIntent (ex. ["card","paypal"]) — le SDK flutter_stripe ne les
    // expose pas via retrievePaymentIntent, la YadonyPaymentSheet les lit donc ici.
    private List<String> paymentMethodTypes;
    // Taux de commission effectif (promo déjà appliqué si présent) — permet au
    // front d'afficher le pourcentage réel sans le recalculer. Null pour les
    // constructeurs historiques (bid : le taux vit déjà sur BidQuoteResponse).
    private BigDecimal commissionRate;
    private boolean promoApplied;

    public PaymentResponse(UUID id, UUID bidId, String clientSecret,
                           BigDecimal amount, BigDecimal commissionAmount, String status) {
        this(id, bidId, clientSecret, amount, commissionAmount, status, null);
    }

    public PaymentResponse(UUID id, UUID bidId, String clientSecret,
                           BigDecimal amount, BigDecimal commissionAmount, String status,
                           String stripePaymentIntentId) {
        this(id, bidId, clientSecret, amount, commissionAmount, status, stripePaymentIntentId, "eur");
    }

    public PaymentResponse(UUID id, UUID bidId, String clientSecret,
                           BigDecimal amount, BigDecimal commissionAmount, String status,
                           String stripePaymentIntentId, String currency) {
        this.id = id;
        this.bidId = bidId;
        this.clientSecret = clientSecret;
        this.amount = amount;
        this.commissionAmount = commissionAmount;
        this.status = status;
        this.stripePaymentIntentId = stripePaymentIntentId;
        this.currency = currency == null ? "eur" : currency.toLowerCase();
    }

    public UUID getId() { return id; }
    public UUID getBidId() { return bidId; }
    public String getClientSecret() { return clientSecret; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getCommissionAmount() { return commissionAmount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public String getStripePaymentIntentId() { return stripePaymentIntentId; }
    public List<String> getPaymentMethodTypes() { return paymentMethodTypes; }
    public void setPaymentMethodTypes(List<String> paymentMethodTypes) {
        this.paymentMethodTypes = paymentMethodTypes;
    }
    public BigDecimal getCommissionRate() { return commissionRate; }
    public void setCommissionRate(BigDecimal commissionRate) { this.commissionRate = commissionRate; }
    public boolean isPromoApplied() { return promoApplied; }
    public void setPromoApplied(boolean promoApplied) { this.promoApplied = promoApplied; }
}
