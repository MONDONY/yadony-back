package com.yadony.api.payments.wallet.dto;

import com.yadony.api.payments.wallet.WalletTransactionEntity;
import java.math.BigDecimal;
import java.time.Instant;

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

    public static WalletTransactionDto from(WalletTransactionEntity tx) {
        return from(tx, null);
    }

    public static WalletTransactionDto from(WalletTransactionEntity tx, String refundStatus) {
        return from(tx, refundStatus, null, null);
    }

    public static WalletTransactionDto from(WalletTransactionEntity tx, String refundStatus,
                                            BigDecimal feeAmount, BigDecimal netAmount) {
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
}
