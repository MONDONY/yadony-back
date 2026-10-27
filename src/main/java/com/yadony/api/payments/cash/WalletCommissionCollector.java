package com.yadony.api.payments.cash;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.cash.dto.FundingAlternativeDto;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateFreshness;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Règle de prélèvement d'une commission espèces sur les portefeuilles du voyageur : portefeuille de
 * la devise du colis d'abord (sans conversion), puis TOUS les autres, du plus gros au plus petit en
 * valeur, convertis au taux du jour (taux snapshoté sur chaque ligne), tout ou rien. La devise active
 * n'a aucun rang particulier : changer la devise d'affichage ne change jamais d'où part l'argent. Un
 * portefeuille que le voyageur a explicitement choisi (devise de complément) passe en premier après
 * celui du colis. Les débits partent dans la transaction de l'appelant.
 *
 * <p>Un portefeuille qui ne règle qu'une partie est VIDÉ exactement (plus de miettes impossibles à
 * dépenser ou à rembourser) ; le dernier est converti au reste exact. Les capacités sont arrondies
 * vers le bas : on ne surestime jamais ce qu'un solde peut couvrir.
 *
 * <p>{@link #plan} lit les soldes SOUS VERROU ({@link WalletService#getBalanceForUpdate}) et exige
 * donc une transaction ouverte ({@code MANDATORY}) : les {@code execute*} qui suivent dans la même
 * transaction ne peuvent plus trouver un solde différent, ce qui garantit le « tout ou rien ». Les
 * portefeuilles sont verrouillés dans l'ordre alphabétique des devises, quel que soit le sens, pour
 * que deux règlements concurrents du même voyageur ne s'interbloquent pas. Aucune exception de débit
 * n'est rattrapée ici : elle remonte et annule la transaction de l'appelant.
 *
 * <p>Sont écartés de la répartition, avec leur motif dans {@link CommissionSplit#excluded()} : un
 * portefeuille gelé par une demande de remboursement en cours ({@code frozen}, {@code debit} le
 * refuserait en 422), un taux de devise flottante trop ancien ({@code rate-stale}) ou absent
 * ({@code rate-missing}).
 *
 * <p>Invariants du moniteur d'intégrité : une seule ligne {@code COMMISSION_DEDUCTED} par couple
 * (colis, devise) (INV-09) et toute ligne convertie porte {@code sourceCurrency} = devise du colis
 * (INV-17) ; chaque portefeuille n'est donc débité qu'une fois.
 */
@Component
public class WalletCommissionCollector {

    private final WalletService walletService;
    private final ExchangeRateService exchangeRateService;
    private final ExchangeRateFreshness exchangeRateFreshness;

    public WalletCommissionCollector(WalletService walletService, ExchangeRateService exchangeRateService,
                                     ExchangeRateFreshness exchangeRateFreshness) {
        this.walletService = walletService;
        this.exchangeRateService = exchangeRateService;
        this.exchangeRateFreshness = exchangeRateFreshness;
    }

    /** Répartition sans devise de complément choisie : tous les portefeuilles, du plus gros au plus petit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CommissionSplit plan(UUID travelerId, String bidCurrency, String activeCurrency, BigDecimal commission) {
        return plan(travelerId, bidCurrency, activeCurrency, null, commission);
    }

    /**
     * @param activeCurrency    devise active du voyageur : sert à l'affichage (champs historiques du
     *                          {@link CommissionSplit}), jamais à ordonner les prélèvements
     * @param preferredCurrency devise de complément choisie explicitement par le voyageur, prélevée
     *                          en premier après le portefeuille du colis ; {@code null} = aucune
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CommissionSplit plan(UUID travelerId, String bidCurrency, String activeCurrency,
                                String preferredCurrency, BigDecimal commission) {
        String bid = normalize(bidCurrency);
        String active = normalize(activeCurrency);
        String preferred = preferredOrNull(preferredCurrency);

        // Ensemble à verrouiller : devise du colis, devise active (soldes des champs historiques) et
        // tout portefeuille à solde positif. Un portefeuille crédité après cette lecture est ignoré,
        // ce qui est sûr. Ordre alphabétique : jamais d'interblocage entre deux règlements croisés.
        Map<String, BigDecimal> balances = new TreeMap<>();
        Set<String> frozen = new TreeSet<>();
        for (String code : candidateCodes(travelerId, bid, active)) {
            if (walletService.isFrozen(travelerId, code)) {
                frozen.add(code);
                balances.put(code, BigDecimal.ZERO);
            } else {
                balances.put(code, walletService.getBalanceForUpdate(travelerId, code));
            }
        }
        return allocate(bid, active, preferred, commission, balances, frozen);
    }

    /**
     * Aperçu EN LECTURE SEULE de la répartition que {@link #plan} produirait : même calcul, mais les
     * soldes sont lus sans verrou et rien n'est débité. Sert à montrer au voyageur, avant qu'il
     * confirme, d'où partira la commission. Indicatif : un solde peut bouger entre l'aperçu et le
     * prélèvement, qui replanifie sous verrou.
     */
    @Transactional(readOnly = true)
    public CommissionSplit preview(UUID travelerId, String bidCurrency, String activeCurrency,
                                   String preferredCurrency, BigDecimal commission) {
        String bid = normalize(bidCurrency);
        String active = normalize(activeCurrency);
        Map<String, BigDecimal> balances = new TreeMap<>();
        Set<String> frozen = new TreeSet<>();
        for (String code : candidateCodes(travelerId, bid, active)) {
            if (walletService.isFrozen(travelerId, code)) {
                frozen.add(code);
                balances.put(code, BigDecimal.ZERO);
            } else {
                balances.put(code, walletService.getBalance(travelerId, code));
            }
        }
        return allocate(bid, active, preferredOrNull(preferredCurrency), commission, balances, frozen);
    }

    /**
     * Taux du jour pour UNE unité de la devise du colis, exprimé dans la devise du portefeuille
     * (ex. colis en euros, portefeuille en F CFA : 655,957). Celui de la table {@code exchange_rates},
     * sans l'arrondi des montants : c'est le taux à montrer au voyageur, pas le taux recalculé et figé
     * sur la ligne du grand livre.
     */
    public BigDecimal rateOfTheDay(String bidCurrency, String walletCurrency) {
        String bid = normalize(bidCurrency);
        String wallet = normalize(walletCurrency);
        if (bid.equals(wallet)) {
            return BigDecimal.ONE;
        }
        BigDecimal bidUnits = "EUR".equals(bid) ? BigDecimal.ONE : exchangeRateService.rateOf(bid);
        BigDecimal walletUnits = "EUR".equals(wallet) ? BigDecimal.ONE : exchangeRateService.rateOf(wallet);
        return walletUnits.divide(bidUnits, 6, RoundingMode.HALF_UP);
    }

    /** Devises à examiner : colis, active, et tout portefeuille supporté à solde positif (ordre alphabétique). */
    private Set<String> candidateCodes(UUID travelerId, String bid, String active) {
        Set<String> codes = new TreeSet<>(List.of(bid, active));
        for (WalletAccountEntity wallet : walletService.getAllBalances(travelerId)) {
            if (wallet.getBalance() != null && wallet.getBalance().signum() > 0
                    && SupportedCurrency.fromCode(normalize(wallet.getCurrency())) != null) {
                codes.add(normalize(wallet.getCurrency()));
            }
        }
        return codes;
    }

    /**
     * Cœur du calcul, sans verrou ni accès au portefeuille : les soldes sont donnés. Sert à
     * {@link #plan} (soldes lus sous verrou) et à un aperçu en lecture seule.
     */
    CommissionSplit allocate(String bid, String active, String preferred, BigDecimal commission,
                             Map<String, BigDecimal> balances, Set<String> frozen) {
        BigDecimal bidBalance = balances.getOrDefault(bid, BigDecimal.ZERO);
        BigDecimal activeBalance = balances.getOrDefault(active, BigDecimal.ZERO);
        BigDecimal fromBid = commission.min(bidBalance).max(BigDecimal.ZERO);
        BigDecimal remaining = commission.subtract(fromBid);

        Map<String, String> excluded = new LinkedHashMap<>();
        for (String code : frozen) {
            excluded.put(code, "frozen");
        }

        List<WalletOption> options = new ArrayList<>();
        if (remaining.signum() > 0) {
            for (Map.Entry<String, BigDecimal> e : balances.entrySet()) {
                String code = e.getKey();
                BigDecimal balance = e.getValue();
                if (code.equals(bid) || balance.signum() <= 0) {
                    continue;
                }
                if (exchangeRateFreshness.isStale(code)) {
                    excluded.put(code, "rate-stale");
                    exchangeRateFreshness.alertStale(code);
                    continue;
                }
                BigDecimal value;
                try {
                    value = exchangeRateService.convert(balance, code, bid, RoundingMode.DOWN);
                } catch (YadonyBusinessException missingRate) {
                    excluded.put(code, "rate-missing");
                    continue;
                }
                if (value.signum() > 0) {
                    options.add(new WalletOption(code, balance, value));
                }
            }
            options.sort(Comparator
                    .comparing((WalletOption o) -> !o.currency().equals(preferred))
                    .thenComparing(WalletOption::valueInBid, Comparator.reverseOrder())
                    .thenComparing(WalletOption::currency));
        }

        BigDecimal available = bidBalance;
        List<CommissionSplit.WalletShare> shares = new ArrayList<>();
        if (bidBalance.signum() > 0) {
            shares.add(new CommissionSplit.WalletShare(bid, bidBalance, bidBalance));
        }
        for (WalletOption o : options) {
            available = available.add(o.valueInBid());
            shares.add(new CommissionSplit.WalletShare(o.currency(), o.balance(), o.valueInBid()));
        }

        List<CommissionLine> lines = new ArrayList<>();
        if (fromBid.signum() > 0) {
            lines.add(new CommissionLine(bid, fromBid, fromBid, null));
        }
        BigDecimal left = remaining;
        for (WalletOption o : options) {
            if (left.signum() == 0) {
                break;
            }
            if (o.valueInBid().compareTo(left) >= 0) {
                // Dernier portefeuille : converti au reste exact. Jamais plus que son solde, même si
                // l'arrondi à l'unité mineure frôle la limite.
                BigDecimal debit = exchangeRateService.convert(left, bid, o.currency()).min(o.balance());
                if (debit.signum() > 0) {
                    lines.add(convertedLine(o.currency(), debit, left));
                }
                left = BigDecimal.ZERO;
            } else {
                // Ne couvre qu'une partie : vidé exactement, il règle sa valeur arrondie vers le bas.
                lines.add(convertedLine(o.currency(), o.balance(), o.valueInBid()));
                left = left.subtract(o.valueInBid());
            }
        }
        boolean covered = left.signum() == 0;

        int activeDecimals = SupportedCurrency.fromCodeOrDefault(active).minorUnit();
        BigDecimal commissionInActive;
        BigDecimal remainingActive;
        BigDecimal legacyRate = null;
        if (bid.equals(active)) {
            commissionInActive = commission;
            remainingActive = remaining;
        } else {
            commissionInActive = commission.signum() == 0
                    ? BigDecimal.ZERO.setScale(activeDecimals)
                    : exchangeRateService.convert(commission, bid, active).setScale(activeDecimals, RoundingMode.HALF_UP);
            if (remaining.signum() == 0) {
                remainingActive = BigDecimal.ZERO.setScale(activeDecimals);
            } else {
                remainingActive = exchangeRateService.convert(remaining, bid, active)
                        .setScale(activeDecimals, RoundingMode.HALF_UP);
                legacyRate = remainingActive.divide(remaining, 6, RoundingMode.HALF_UP);
            }
        }
        return new CommissionSplit(bid, commission, fromBid, remaining, active, remainingActive, legacyRate,
                bidBalance, activeBalance, covered, commissionInActive,
                covered ? lines : List.of(), available, shares, excluded);
    }

    /** Lignes de la répartition pour l'audit : une entrée par portefeuille débité. */
    public static List<Map<String, String>> auditLines(CommissionSplit split) {
        List<Map<String, String>> out = new ArrayList<>();
        for (CommissionLine line : split.lines()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("currency", line.walletCurrency());
            entry.put("debit", line.debitAmount().toPlainString());
            entry.put("coveredInBid", line.coveredInBid().toPlainString());
            out.add(entry);
        }
        return out;
    }

    private static CommissionLine convertedLine(String currency, BigDecimal debit, BigDecimal coveredInBid) {
        BigDecimal rate = debit.divide(coveredInBid, 6, RoundingMode.HALF_UP);
        return new CommissionLine(currency, debit, coveredInBid, rate);
    }

    /** Portefeuille utilisable : son solde et ce qu'il règle dans la devise du colis (arrondi vers le bas). */
    private record WalletOption(String currency, BigDecimal balance, BigDecimal valueInBid) {
    }

    /**
     * Portefeuilles autres que celui du colis et que {@code complementCurrency} qui couvriraient,
     * seuls, ce qui reste après le portefeuille du colis : proposés au voyageur sur un « solde
     * insuffisant » (FLUTTER-CG : recharge mobile money en XOF, trajet et devise active en EUR).
     * Lecture SANS verrou, pour affichage : le débit, s'il est choisi, repasse par {@link #plan}
     * avec cette devise en complément. Un portefeuille gelé est ignoré, comme dans {@link #plan}.
     */
    public List<FundingAlternativeDto> alternatives(UUID travelerId, String bidCurrency,
                                                    String complementCurrency, BigDecimal commission) {
        String bid = normalize(bidCurrency);
        String complement = normalize(complementCurrency);
        BigDecimal bidBalance = walletService.isFrozen(travelerId, bid)
                ? BigDecimal.ZERO : walletService.getBalance(travelerId, bid);
        if (bidBalance == null) {
            bidBalance = BigDecimal.ZERO;
        }
        BigDecimal remainingBid = commission.subtract(commission.min(bidBalance).max(BigDecimal.ZERO));
        List<FundingAlternativeDto> result = new ArrayList<>();
        if (remainingBid.signum() <= 0) {
            return result;
        }
        for (var wallet : walletService.getAllBalances(travelerId)) {
            String code = normalize(wallet.getCurrency());
            if (code.equals(bid) || code.equals(complement) || SupportedCurrency.fromCode(code) == null
                    || wallet.getBalance() == null || wallet.getBalance().signum() <= 0
                    || walletService.isFrozen(travelerId, code) || exchangeRateFreshness.isStale(code)) {
                continue;
            }
            int decimals = SupportedCurrency.fromCodeOrDefault(code).minorUnit();
            BigDecimal required = exchangeRateService.convert(remainingBid, bid, code)
                    .setScale(decimals, RoundingMode.HALF_UP);
            if (wallet.getBalance().compareTo(required) >= 0) {
                result.add(new FundingAlternativeDto(code, wallet.getBalance(), required));
            }
        }
        return result;
    }

    public void executeForBid(CommissionSplit s, UUID travelerId, UUID bidId) {
        requireCovered(s);
        if (s.commission().signum() == 0) {
            walletService.debit(travelerId, s.activeCurrency(), BigDecimal.ZERO,
                    WalletTransactionType.COMMISSION_DEDUCTED, bidId);
            return;
        }
        for (CommissionLine line : s.lines()) {
            if (line.converted()) {
                walletService.debit(travelerId, line.walletCurrency(), line.debitAmount(),
                        WalletTransactionType.COMMISSION_DEDUCTED, bidId,
                        s.bidCurrency(), line.coveredInBid(), line.appliedRate());
            } else {
                walletService.debit(travelerId, line.walletCurrency(), line.debitAmount(),
                        WalletTransactionType.COMMISSION_DEDUCTED, bidId);
            }
        }
    }

    public void executeForNegotiation(CommissionSplit s, UUID travelerId, String paymentRef, String idempotencyKeyPrefix) {
        requireCovered(s);
        if (s.commission().signum() == 0) {
            walletService.debit(travelerId, s.activeCurrency(), BigDecimal.ZERO,
                    WalletTransactionType.COMMISSION_DEDUCTED, paymentRef, idempotencyKeyPrefix);
            return;
        }
        for (CommissionLine line : s.lines()) {
            if (line.converted()) {
                walletService.debit(travelerId, line.walletCurrency(), line.debitAmount(),
                        WalletTransactionType.COMMISSION_DEDUCTED, paymentRef,
                        keyFor(idempotencyKeyPrefix, "_", line, s),
                        s.bidCurrency(), line.coveredInBid(), line.appliedRate());
            } else {
                walletService.debit(travelerId, line.walletCurrency(), line.debitAmount(),
                        WalletTransactionType.COMMISSION_DEDUCTED, paymentRef, idempotencyKeyPrefix);
            }
        }
    }

    /**
     * Clé d'idempotence d'une ligne convertie. La devise active garde son suffixe historique
     * ({@code _active} / {@code -active}) : un paiement déjà passé avant ce changement a écrit sa ligne
     * sous cette clé, un rejeu ne doit pas la débiter une seconde fois. Tout autre portefeuille porte
     * son code de devise.
     */
    private static String keyFor(String base, String separator, CommissionLine line, CommissionSplit s) {
        return base + separator + (line.walletCurrency().equals(s.activeCurrency())
                ? "active" : line.walletCurrency());
    }

    /**
     * Commission du supplément de prix d'un colis espèces : mêmes portefeuilles et même ordre que
     * {@link #executeForBid}, mais en {@code SUPPLEMENT_COMMISSION_DEDUCTED} (jamais
     * {@code COMMISSION_DEDUCTED}, réservé à la commission principale du colis : INV-09), avec
     * {@code bid_id} du colis, {@code payment_ref = "supplement-<id>"} et une clé d'idempotence par
     * portefeuille ({@code supplement-commission-<id>}, {@code …-active} pour le complément).
     * Aucune ligne à zéro : une commission nulle ne débite rien.
     */
    public void executeForSupplement(CommissionSplit s, UUID travelerId, UUID bidId, UUID supplementId) {
        executeForExtra(s, travelerId, bidId, WalletTransactionType.SUPPLEMENT_COMMISSION_DEDUCTED,
                supplementPaymentRef(supplementId), "supplement-commission-" + supplementId);
    }

    /**
     * Commission d'une modification de colis payée en espèces, prélevée au voyageur quand il l'accepte :
     * comme {@link #executeForSupplement}, en {@code AMENDMENT_COMMISSION_DEDUCTED},
     * {@code payment_ref = "amendment-<id>"} et clés {@code amendment-commission-<id>} (puis
     * {@code …-active} pour le complément).
     */
    public void executeForAmendment(CommissionSplit s, UUID travelerId, UUID bidId, UUID amendmentId) {
        executeForExtra(s, travelerId, bidId, WalletTransactionType.AMENDMENT_COMMISSION_DEDUCTED,
                amendmentPaymentRef(amendmentId), "amendment-commission-" + amendmentId);
    }

    /** {@code payment_ref} des lignes de commission d'un supplément (lecture pour le recrédit). */
    public static String supplementPaymentRef(UUID supplementId) {
        return "supplement-" + supplementId;
    }

    /** {@code payment_ref} des lignes de commission d'une modification (lecture pour le recrédit). */
    public static String amendmentPaymentRef(UUID amendmentId) {
        return "amendment-" + amendmentId;
    }

    /** Débit d'une commission annexe (supplément, modification) : un type, une référence et une clé propres. */
    private void executeForExtra(CommissionSplit s, UUID travelerId, UUID bidId, WalletTransactionType type,
                                 String paymentRef, String key) {
        requireCovered(s);
        for (CommissionLine line : s.lines()) {
            if (line.converted()) {
                walletService.debit(travelerId, line.walletCurrency(), line.debitAmount(), type, bidId, paymentRef,
                        keyFor(key, "-", line, s), s.bidCurrency(), line.coveredInBid(), line.appliedRate());
            } else {
                walletService.debit(travelerId, line.walletCurrency(), line.debitAmount(), type, bidId, paymentRef,
                        key, null, null, null);
            }
        }
    }

    /**
     * Devise de complément d'une commission annexe (supplément, modification) : celle choisie par le voyageur
     * (code supporté, sinon 422 {@code funding-currency-invalid}), sa devise active sinon.
     */
    public static String complementCurrency(ActiveCurrencyResolver activeCurrencyResolver, UUID travelerId,
                                            String fundingCurrency) {
        if (fundingCurrency == null || fundingCurrency.isBlank()) {
            return normalize(activeCurrencyResolver.resolve(travelerId));
        }
        String code = normalize(fundingCurrency);
        if (SupportedCurrency.fromCode(code) == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "funding-currency-invalid",
                    "Invalid Funding Currency", "Devise de prélèvement inconnue");
        }
        return code;
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

    /**
     * Devise de complément choisie par le voyageur, normalisée, ou {@code null} quand il n'en a pas choisi
     * (champ absent ou vide) : la répartition prend alors tous les portefeuilles du plus gros au plus petit.
     */
    public static String preferredOrNull(String fundingCurrency) {
        return fundingCurrency == null || fundingCurrency.isBlank() ? null : normalize(fundingCurrency);
    }

    /** Code de devise comparable : sans espaces, en majuscules ({@code ""} si absent). */
    public static String normalize(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    }
}
