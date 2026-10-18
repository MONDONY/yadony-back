package com.yadony.api.payments.wallet;

import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayCountries;
import com.yadony.api.payments.pawapay.dto.PawapayWalletBalance;
import com.yadony.api.payments.wallet.fees.PawapayFeeTable;
import com.yadony.api.payments.wallet.fees.PawapayRefundSafeAmount;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fonds disponibles sur le wallet pawaPay de Yadony pour rembourser une recharge mobile money :
 * solde lu en direct ({@code GET /v2/wallet-balances}) pour le pays et la devise du dépôt
 * d'origine, et montant sûr qu'il permet d'envoyer à un opérateur
 * ({@link PawapayRefundSafeAmount}). Sert à la relance admin d'un remboursement passé en
 * manuel ({@link WalletRefundRetryService}).
 *
 * <p>pawaPay répond le pays en ISO alpha-3 (CIV), les opérations le stockent en alpha-2 (CI) :
 * la comparaison passe par {@link PawapayCountries}. Une lecture impossible (réseau, réponse
 * illisible, aucun wallet pour ce pays et cette devise) rend un {@link Optional} vide, jamais
 * une exception : à l'appelant de décider s'il refuse ou continue.
 */
@Component
public class PawapayRefundFunds {

    private static final Logger log = LoggerFactory.getLogger(PawapayRefundFunds.class);

    private final PawapayClient client;
    private final PawapayFeeTable feeTable;
    private final BigDecimal safetyMarginPercent;

    public PawapayRefundFunds(PawapayClient client, PawapayFeeTable feeTable,
                              @Value("${yadony.pawapay.refund-safety-margin-percent:1.0}")
                              BigDecimal safetyMarginPercent) {
        this.client = client;
        this.feeTable = feeTable;
        this.safetyMarginPercent = safetyMarginPercent == null ? BigDecimal.ZERO : safetyMarginPercent;
    }

    /** Solde du wallet pawaPay pour {@code countryAlpha2} et {@code currency} ; vide s'il est illisible. */
    public Optional<BigDecimal> balance(String countryAlpha2, String currency) {
        if (countryAlpha2 == null || currency == null) {
            return Optional.empty();
        }
        List<PawapayWalletBalance> balances;
        try {
            balances = client.walletBalances();
        } catch (RuntimeException e) {
            log.warn("pawaPay : solde du wallet {} {} illisible ({})", countryAlpha2, currency, e.toString());
            return Optional.empty();
        }
        String country = countryAlpha2.trim().toUpperCase(Locale.ROOT);
        Optional<BigDecimal> found = balances == null ? Optional.empty() : balances.stream()
                .filter(b -> b != null && b.balance() != null && currency.equalsIgnoreCase(b.currency()))
                .filter(b -> country.equals(PawapayCountries.toAlpha2(b.countryAlpha3())))
                .map(PawapayWalletBalance::balance)
                .findFirst();
        if (found.isEmpty()) {
            log.warn("pawaPay : aucun wallet {} {} dans la réponse des soldes", country, currency);
        }
        return found;
    }

    /** Taux retenu pour l'opérateur : {@code refund-percent} du barème + marge de sécurité. */
    public BigDecimal ratePercent(String provider) {
        return PawapayRefundSafeAmount.ratePercent(feeTable.rateFor(provider).refundPercent(), safetyMarginPercent);
    }

    /** Montant sûr pour {@code provider} sur un solde déjà lu (voir {@link PawapayRefundSafeAmount#maxSafe}). */
    public BigDecimal maxSafe(BigDecimal walletBalance, String provider, String currency) {
        return PawapayRefundSafeAmount.maxSafe(walletBalance, ratePercent(provider), currency);
    }

    /** Manque pour envoyer {@code amount} en sécurité (voir {@link PawapayRefundSafeAmount#topUpNeeded}). */
    public BigDecimal topUpNeeded(BigDecimal amount, BigDecimal walletBalance, String provider, String currency) {
        return PawapayRefundSafeAmount.topUpNeeded(amount, walletBalance, ratePercent(provider), currency);
    }
}
