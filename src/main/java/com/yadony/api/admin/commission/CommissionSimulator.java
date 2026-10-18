package com.yadony.api.admin.commission;

import com.yadony.api.admin.dto.CommissionSimulationRequest;
import com.yadony.api.admin.dto.CommissionSimulationResponse;
import com.yadony.api.admin.dto.CommissionSimulationResponse.Rail;
import com.yadony.api.admin.dto.CommissionSimulationResponse.RailCost;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionCalculator;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridDraft;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionGridWarning;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionResult;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.wallet.fees.PawapayFeeTable;
import com.yadony.api.payments.wallet.fees.PawapayFeeTable.Rate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Simulateur de marge de l'onglet Tarification (spec 2026-10-18 §5.5) : pour chaque net, la
 * commission d'une grille brouillon (ou existante) face à la grille en vigueur, puis, par rail de
 * paiement, le coût du prestataire et la marge hors taxes de Yadony.
 *
 * <p>Les coûts sont ceux du tarif public (voir {@link ProviderCostProperties}) : des ordres de
 * grandeur pour décider, jamais un prix. La marge se calcule sur les coûts non arrondis ; les
 * montants affichés le sont à l'unité mineure de la devise, la marge à 4 décimales.
 */
@Service
public class CommissionSimulator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DEFAULT_VAT_PERCENT = new BigDecimal("20");
    private static final int MARGIN_SCALE = 4;
    private static final MathContext EXACT = new MathContext(20, RoundingMode.HALF_UP);

    /** Pays pawaPay (suffixe de la clé d'opérateur, ex. {@code ORANGE_CIV}) par devise de la zone CFA. */
    private static final Map<SupportedCurrency, List<String>> COUNTRIES_BY_CURRENCY = Map.of(
            SupportedCurrency.XOF, List.of("CIV", "SEN", "BEN", "BFA", "MLI", "TGO", "NER", "GNB"),
            SupportedCurrency.XAF, List.of("CMR", "GAB", "COG", "TCD", "CAF", "GNQ"));

    private final CommissionGridService grids;
    private final ProviderCostProperties costs;
    private final PawapayFeeTable pawapayFees;

    public CommissionSimulator(CommissionGridService grids, ProviderCostProperties costs,
                               PawapayFeeTable pawapayFees) {
        this.grids = grids;
        this.costs = costs;
        this.pawapayFees = pawapayFees;
    }

    public CommissionSimulationResponse simulate(CommissionSimulationRequest request) {
        SupportedCurrency currency = request.currency();
        BigDecimal vatPercent = request.vatRatePercent() == null ? DEFAULT_VAT_PERCENT : request.vatRatePercent();
        if (vatPercent.signum() < 0) {
            throw invalid("vatRatePercent", "La TVA ne peut pas être négative.");
        }

        List<CommissionGridWarning> warnings = List.of();
        CommissionGrid proposed;
        if (request.draft() != null && request.gridId() == null) {
            CommissionGridDraft draft = request.draft().toDraft();
            warnings = grids.warningsFor(draft);
            proposed = new CommissionGrid(null, draft.effectiveFrom(), draft.tiers(), draft.minimums());
        } else if (request.gridId() != null && request.draft() == null) {
            proposed = grids.require(request.gridId());
        } else {
            throw invalid("draft", "Fournir soit une grille brouillon, soit un gridId.");
        }
        CommissionGrid reference = (request.effectiveFrom() == null
                ? grids.current() : grids.activeAt(request.effectiveFrom()))
                .orElseGet(grids::flatPlatformGrid);

        List<CommissionSimulationResponse.Row> rows = new ArrayList<>();
        for (BigDecimal net : request.nets()) {
            if (net.setScale(currency.minorUnit(), RoundingMode.HALF_UP).signum() <= 0) {
                throw invalid("nets", "Le net doit valoir au moins une unité de la devise.");
            }
            CommissionResult now = CommissionCalculator.compute(net, currency, reference, CommissionReductions.NONE);
            CommissionResult next = CommissionCalculator.compute(net, currency, proposed, CommissionReductions.NONE);
            rows.add(new CommissionSimulationResponse.Row(next.net(),
                    new CommissionSimulationResponse.Current(now.commission(), now.gross()),
                    new CommissionSimulationResponse.Draft(next.commission(), next.gross(), next.minimumApplied()),
                    rails(next, currency, vatPercent)));
        }
        return new CommissionSimulationResponse(rows, warnings,
                round(costs.connectActiveAccountMonthlyEur().multiply(currency.unitsPerEur()), currency));
    }

    private List<RailCost> rails(CommissionResult quote, SupportedCurrency currency, BigDecimal vatPercent) {
        BigDecimal net = quote.net();
        BigDecimal gross = quote.gross();
        BigDecimal commissionExVat = quote.commission().divide(BigDecimal.ONE.add(vatPercent.divide(HUNDRED, EXACT)), EXACT);

        BigDecimal payout = percent(net, costs.connectPayoutPercent())
                .add(costs.connectPayoutFixedEur().multiply(currency.unitsPerEur()));
        List<RailCost> rails = new ArrayList<>();
        rails.add(rail(Rail.CARD_STANDARD, card(gross, costs.cardStandardPercent(), currency).add(payout), commissionExVat, currency));
        rails.add(rail(Rail.CARD_PREMIUM, card(gross, costs.cardPremiumPercent(), currency).add(payout), commissionExVat, currency));
        rails.add(rail(Rail.CARD_INTERNATIONAL, card(gross, costs.cardInternationalPercent(), currency).add(payout), commissionExVat, currency));

        List<BigDecimal> mobileMoney = operatorRates(currency).stream()
                .map(rate -> percent(gross, rate.depositPercent()).add(percent(net, rate.refundPercent())))
                .toList();
        if (!mobileMoney.isEmpty()) {
            rails.add(rail(Rail.MOBILE_MONEY_WORST, mobileMoney.stream().max(Comparator.naturalOrder()).orElseThrow(), commissionExVat, currency));
            rails.add(rail(Rail.MOBILE_MONEY_BEST, mobileMoney.stream().min(Comparator.naturalOrder()).orElseThrow(), commissionExVat, currency));
        }

        // Espèces : Yadony prélève sa commission sur le solde, rechargé par carte standard.
        rails.add(rail(Rail.CASH, card(quote.commission(), costs.cardStandardPercent(), currency), commissionExVat, currency));
        return rails;
    }

    /** Frais d'un encaissement carte de {@code amount} : pourcentage plus frais fixe converti dans la devise. */
    private BigDecimal card(BigDecimal amount, BigDecimal percent, SupportedCurrency currency) {
        return percent(amount, percent).add(costs.cardFixedEur().multiply(currency.unitsPerEur()));
    }

    private static BigDecimal percent(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent).divide(HUNDRED, EXACT);
    }

    private static RailCost rail(Rail rail, BigDecimal cost, BigDecimal commissionExVat, SupportedCurrency currency) {
        return new RailCost(rail, round(cost, currency),
                commissionExVat.subtract(cost).setScale(MARGIN_SCALE, RoundingMode.HALF_UP));
    }

    private static BigDecimal round(BigDecimal amount, SupportedCurrency currency) {
        return amount.setScale(currency.minorUnit(), RoundingMode.HALF_UP);
    }

    /**
     * Taux pawaPay des opérateurs de la devise, repérés par le pays qui termine leur clé. Les clés
     * sont normalisées comme dans {@link PawapayFeeTable} : Spring peut retirer les séparateurs
     * ({@code ORANGE_CIV} lié en {@code ORANGECIV}).
     */
    private List<Rate> operatorRates(SupportedCurrency currency) {
        List<String> countries = COUNTRIES_BY_CURRENCY.getOrDefault(currency, List.of());
        if (countries.isEmpty() || pawapayFees == null || pawapayFees.providers() == null) {
            return List.of();
        }
        return pawapayFees.providers().entrySet().stream()
                .filter(e -> {
                    String key = e.getKey().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
                    return countries.stream().anyMatch(key::endsWith);
                })
                .map(Map.Entry::getValue)
                .toList();
    }

    private static YadonyBusinessException invalid(String field, String message) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "validation", "Validation Error",
                "Simulation invalide", Map.of("violations", Map.of(field, message)));
    }
}
