package com.yadony.api.payments.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet_transactions")
public class WalletTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private WalletTransactionType type;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 10, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "bid_id")
    private UUID bidId;

    @Column(name = "payment_ref", length = 255)
    private String paymentRef;

    @Column(name = "idempotency_key", length = 255, unique = true)
    private String idempotencyKey;

    /**
     * Devise d'origine de la commission avant conversion (ex. la devise de l'annonce),
     * uniquement renseignée quand une conversion a eu lieu. {@code NULL} sur toute
     * transaction ne résultant pas d'une conversion (y compris les transactions
     * historiques antérieures à cette colonne).
     */
    @Column(name = "source_currency", length = 3)
    private String sourceCurrency;

    /**
     * Montant d'origine avant conversion, dans {@link #sourceCurrency}. Snapshoté au
     * moment du prélèvement : ne doit jamais être recalculé a posteriori.
     */
    @Column(name = "source_amount", precision = 19, scale = 4)
    private BigDecimal sourceAmount;

    /**
     * Taux de change appliqué ({@code amount} / {@code sourceAmount}), figé au moment
     * du prélèvement. Un changement ultérieur du taux administré dans
     * {@code exchange_rates} ne doit jamais rejaillir sur cette valeur déjà persistée.
     */
    @Column(name = "applied_rate", precision = 18, scale = 6)
    private BigDecimal appliedRate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public WalletTransactionType getType() { return type; }
    public void setType(WalletTransactionType type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }
    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }
    public String getPaymentRef() { return paymentRef; }
    public void setPaymentRef(String paymentRef) { this.paymentRef = paymentRef; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getSourceCurrency() { return sourceCurrency; }
    public void setSourceCurrency(String sourceCurrency) { this.sourceCurrency = sourceCurrency; }
    public BigDecimal getSourceAmount() { return sourceAmount; }
    public void setSourceAmount(BigDecimal sourceAmount) { this.sourceAmount = sourceAmount; }
    public BigDecimal getAppliedRate() { return appliedRate; }
    public void setAppliedRate(BigDecimal appliedRate) { this.appliedRate = appliedRate; }
    public Instant getCreatedAt() { return createdAt; }
}
