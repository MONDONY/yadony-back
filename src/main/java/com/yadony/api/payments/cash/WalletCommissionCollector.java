package com.yadony.api.payments.cash;

import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Règle de prélèvement d'une commission espèces sur les portefeuilles du voyageur :
 * portefeuille de la devise du colis d'abord (sans conversion), puis complément sur la devise
 * active (converti au taux courant, taux snapshoté sur la ligne), tout ou rien. Aucun autre
 * portefeuille n'est touché. Les débits partent dans la transaction de l'appelant.
 *
 * <p>{@link #plan} lit les soldes SOUS VERROU ({@link WalletService#getBalanceForUpdate}) et
 * exige donc une transaction ouverte ({@code MANDATORY}) : les {@code execute*} qui suivent
 * dans la même transaction ne peuvent plus trouver un solde différent, ce qui garantit le
 * « tout ou rien » — sans cela, {@code WalletService.debit} conservant sa ligne sur
 * {@code InsufficientWalletBalanceException} ({@code noRollbackFor}), un second débit refusé
 * laissait le premier commité. Les deux portefeuilles sont verrouillés dans l'ordre
 * alphabétique des devises, quel que soit le sens colis → actif, pour que deux règlements
 * concurrents du même voyageur ne s'interbloquent pas. Aucune exception de débit n'est
 * rattrapée ici : elle remonte et annule la transaction de l'appelant.
 *
 * <p>Un portefeuille gelé par une demande de remboursement en cours
 * ({@link WalletService#isFrozen}) est traité comme VIDE : {@code debit} le refuserait
 * (422 {@code wallet-refund-pending}), il ne doit donc entrer dans aucune répartition. Un
 * colis dans la devise gelée reste ainsi réglable entièrement sur la devise active, comme
 * avant cette règle.
 */
@Component
public class WalletCommissionCollector {

    private final WalletService walletService;
    private final ExchangeRateService exchangeRateService;

    public WalletCommissionCollector(WalletService walletService, ExchangeRateService exchangeRateService) {
        this.walletService = walletService;
        this.exchangeRateService = exchangeRateService;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CommissionSplit plan(UUID travelerId, String bidCurrency, String activeCurrency, BigDecimal commission) {
        String bid = normalize(bidCurrency);
        String active = normalize(activeCurrency);
        int activeDecimals = SupportedCurrency.fromCodeOrDefault(active).minorUnit();

        if (bid.equals(active)) {
            BigDecimal balance = lockedBalance(travelerId, active);
            BigDecimal taken = commission.min(balance).max(BigDecimal.ZERO);
            BigDecimal remaining = commission.subtract(taken);
            return new CommissionSplit(bid, commission, taken, remaining, active, remaining, null,
                    balance, balance, balance.compareTo(commission) >= 0, commission);
        }

        // Ordre de verrouillage stable (alphabétique) pour éviter l'interblocage entre deux
        // règlements concurrents du même voyageur (colis XOF/actif EUR contre colis EUR/actif XOF).
        Map<String, BigDecimal> balances = new TreeMap<>();
        for (String code : new TreeSet<>(List.of(bid, active))) {
            balances.put(code, lockedBalance(travelerId, code));
        }
        BigDecimal bidBalance = balances.get(bid);
        BigDecimal activeBalance = balances.get(active);
        BigDecimal fromBid = commission.min(bidBalance).max(BigDecimal.ZERO);
        BigDecimal remainingBid = commission.subtract(fromBid);
        // Commission totale dans la devise active : affichage seulement (requiredCommission),
        // jamais un montant débité. Calculée même quand le portefeuille du colis couvre tout.
        BigDecimal commissionInActive = commission.signum() == 0
                ? BigDecimal.ZERO.setScale(activeDecimals)
                : exchangeRateService.convert(commission, bid, active).setScale(activeDecimals, RoundingMode.HALF_UP);

        if (remainingBid.signum() == 0) {
            return new CommissionSplit(bid, commission, fromBid, remainingBid, active,
                    BigDecimal.ZERO.setScale(activeDecimals), null, bidBalance, activeBalance, true,
                    commissionInActive);
        }

        BigDecimal remainingActive = exchangeRateService.convert(remainingBid, bid, active)
                .setScale(activeDecimals, RoundingMode.HALF_UP);
        BigDecimal appliedRate = remainingActive.divide(remainingBid, 6, RoundingMode.HALF_UP);
        boolean covered = activeBalance.compareTo(remainingActive) >= 0;
        return new CommissionSplit(bid, commission, fromBid, remainingBid, active, remainingActive, appliedRate,
                bidBalance, activeBalance, covered, commissionInActive);
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
        // Reste converti nul (ex. 1 XOF → 0,00 EUR) : pas de ligne à zéro dans le grand livre.
        if (s.remainingBid().signum() > 0 && s.remainingActive().signum() > 0) {
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
        if (s.remainingBid().signum() > 0 && s.remainingActive().signum() > 0) {
            walletService.debit(travelerId, s.activeCurrency(), s.remainingActive(),
                    WalletTransactionType.COMMISSION_DEDUCTED, paymentRef, idempotencyKeyPrefix + "_active",
                    s.bidCurrency(), s.remainingBid(), s.appliedRate());
        }
    }

    /** Solde débitable sous verrou : zéro si la devise est gelée (jamais verrouillée alors). */
    private BigDecimal lockedBalance(UUID travelerId, String code) {
        if (walletService.isFrozen(travelerId, code)) {
            return BigDecimal.ZERO;
        }
        return walletService.getBalanceForUpdate(travelerId, code);
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
