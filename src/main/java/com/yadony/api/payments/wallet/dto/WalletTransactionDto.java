package com.yadony.api.payments.wallet.dto;

import com.yadony.api.payments.wallet.WalletTransactionEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class WalletTransactionDto {

    private String type;
    private BigDecimal amount;
    /** Devise de la transaction : la liste mêle les portefeuilles d'un même utilisateur. */
    private String currency;
    private BigDecimal balanceAfter;
    private String paymentRef;
    private Instant createdAt;
    private String refundStatus;
    /**
     * Frais retenus et net versé d'un {@code SELF_REFUND_OUT} (additifs). Remplis seulement si la
     * transaction s'apparie sans ambiguïté à sa demande de remboursement
     * ({@code WalletSelfRefundService#refundFeesByTransactionId}), {@code null} sinon.
     */
    private BigDecimal feeAmount;
    private BigDecimal netAmount;
    /**
     * Colis lié à la ligne (FLUTTER-QP), nom JSON {@code bidId} figé (contrat app) : commission
     * prélevée ou remboursée, y compris la commission d'un fil de négociation résolue vers le
     * colis qui en est issu ({@code WalletTransactionBidLinks}). {@code null} sans colis.
     */
    private UUID bidId;
    /**
     * Origine d'une ligne convertie (additifs) : une commission prélevée sur un portefeuille autre que celui
     * de la devise du colis porte la devise et le montant de la part de commission qu'elle règle
     * ({@code wallet_transactions.source_currency} / {@code source_amount}). L'app y lit « pour 450 F CFA ».
     * {@code null} (champs absents du JSON) quand rien n'a été converti. Jamais le taux : le taux figé est
     * recalculé sur des montants arrondis et paraîtrait faux à l'utilisateur.
     */
    private String sourceCurrency;
    private BigDecimal sourceAmount;

    public static WalletTransactionDto from(WalletTransactionEntity tx, String refundStatus,
                                            BigDecimal feeAmount, BigDecimal netAmount, UUID bidId) {
        WalletTransactionDto dto = new WalletTransactionDto();
        dto.type = tx.getType().name();
        dto.amount = tx.getAmount();
        dto.currency = tx.getCurrency() != null ? tx.getCurrency().toUpperCase(java.util.Locale.ROOT) : null;
        dto.balanceAfter = tx.getBalanceAfter();
        dto.paymentRef = tx.getPaymentRef();
        dto.createdAt = tx.getCreatedAt();
        dto.refundStatus = refundStatus;
        dto.feeAmount = feeAmount;
        dto.netAmount = netAmount;
        dto.bidId = bidId;
        dto.sourceCurrency = tx.getSourceCurrency() != null
                ? tx.getSourceCurrency().toUpperCase(java.util.Locale.ROOT) : null;
        dto.sourceAmount = tx.getSourceAmount();
        return dto;
    }

    public String getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getPaymentRef() { return paymentRef; }
    public Instant getCreatedAt() { return createdAt; }
    public String getRefundStatus() { return refundStatus; }
    public BigDecimal getFeeAmount() { return feeAmount; }
    public BigDecimal getNetAmount() { return netAmount; }
    public UUID getBidId() { return bidId; }
    public String getSourceCurrency() { return sourceCurrency; }
    public BigDecimal getSourceAmount() { return sourceAmount; }
}
