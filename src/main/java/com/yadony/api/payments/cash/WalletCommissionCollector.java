package com.yadony.api.payments.cash;

import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.UUID;

/**
 * Règle de prélèvement d'une commission espèces sur les portefeuilles du voyageur :
 * portefeuille de la devise du colis d'abord (sans conversion), puis complément sur la devise
 * active (converti au taux courant, taux snapshoté sur la ligne), tout ou rien. Aucun autre
 * portefeuille n'est touché. Les débits partent dans la transaction de l'appelant.
 */
@Component
public class WalletCommissionCollector {

    private final WalletService walletService;
    private final ExchangeRateService exchangeRateService;

    public WalletCommissionCollector(WalletService walletService, ExchangeRateService exchangeRateService) {
        this.walletService = walletService;
        this.exchangeRateService = exchangeRateService;
    }

    public CommissionSplit plan(UUID travelerId, String bidCurrency, String activeCurrency, BigDecimal commission) {
        String bid = normalize(bidCurrency);
        String active = normalize(activeCurrency);
        int activeDecimals = SupportedCurrency.fromCodeOrDefault(active).minorUnit();

        if (bid.equals(active)) {
            BigDecimal balance = walletService.getBalance(travelerId, active);
            BigDecimal taken = commission.min(balance).max(BigDecimal.ZERO);
            BigDecimal remaining = commission.subtract(taken);
            return new CommissionSplit(bid, commission, taken, remaining, active, remaining, null,
                    balance, balance, balance.compareTo(commission) >= 0);
        }

        BigDecimal bidBalance = walletService.getBalance(travelerId, bid);
        BigDecimal activeBalance = walletService.getBalance(travelerId, active);
        BigDecimal fromBid = commission.min(bidBalance).max(BigDecimal.ZERO);
        BigDecimal remainingBid = commission.subtract(fromBid);

        if (remainingBid.signum() == 0) {
            return new CommissionSplit(bid, commission, fromBid, remainingBid, active,
                    BigDecimal.ZERO.setScale(activeDecimals), null, bidBalance, activeBalance, true);
        }

        BigDecimal remainingActive = exchangeRateService.convert(remainingBid, bid, active)
                .setScale(activeDecimals, RoundingMode.HALF_UP);
        BigDecimal appliedRate = remainingActive.divide(remainingBid, 6, RoundingMode.HALF_UP);
        boolean covered = activeBalance.compareTo(remainingActive) >= 0;
        return new CommissionSplit(bid, commission, fromBid, remainingBid, active, remainingActive, appliedRate,
                bidBalance, activeBalance, covered);
    }

    public void executeForBid(CommissionSplit s, UUID travelerId, UUID bidId) {
        requireCovered(s);
        if (s.commission().signum() == 0) {
            walletService.debit(travelerId, s.activeCurrency(), BigDecimal.ZERO,
                    WalletTransactionType.COMMISSION_DEDUCTED, bidId);
            return;
        }
        if (s.fromBidWallet().signum() > 0) {
            walletService.debit(travelerId, s.bidCurrency(), s.fromBidWallet(),
                    WalletTransactionType.COMMISSION_DEDUCTED, bidId);
        }
        if (s.remainingBid().signum() > 0) {
            walletService.debit(travelerId, s.activeCurrency(), s.remainingActive(),
                    WalletTransactionType.COMMISSION_DEDUCTED, bidId,
                    s.bidCurrency(), s.remainingBid(), s.appliedRate());
        }
    }

    public void executeForNegotiation(CommissionSplit s, UUID travelerId, String paymentRef, String idempotencyKeyPrefix) {
        requireCovered(s);
        if (s.commission().signum() == 0) {
            walletService.debit(travelerId, s.activeCurrency(), BigDecimal.ZERO,
                    WalletTransactionType.COMMISSION_DEDUCTED, paymentRef, idempotencyKeyPrefix);
            return;
        }
        if (s.fromBidWallet().signum() > 0) {
            walletService.debit(travelerId, s.bidCurrency(), s.fromBidWallet(),
                    WalletTransactionType.COMMISSION_DEDUCTED, paymentRef, idempotencyKeyPrefix);
        }
        if (s.remainingBid().signum() > 0) {
            walletService.debit(travelerId, s.activeCurrency(), s.remainingActive(),
                    WalletTransactionType.COMMISSION_DEDUCTED, paymentRef, idempotencyKeyPrefix + "_active",
                    s.bidCurrency(), s.remainingBid(), s.appliedRate());
        }
    }

    private static void requireCovered(CommissionSplit s) {
        if (!s.covered()) {
            throw new IllegalStateException("Commission non couverte par les portefeuilles : aucun débit");
        }
    }

    private static String normalize(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    }
}
