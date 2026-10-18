package com.yadony.api.admin.commission;

import com.yadony.api.admin.dto.CommissionGridRequest;
import com.yadony.api.admin.dto.CommissionSimulationRequest;
import com.yadony.api.admin.dto.CommissionSimulationResponse;
import com.yadony.api.admin.dto.CommissionSimulationResponse.Rail;
import com.yadony.api.admin.dto.CommissionSimulationResponse.RailCost;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridDraft;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionGridWarning;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.wallet.fees.PawapayFeeTable;
import com.yadony.api.payments.wallet.fees.PawapayFeeTable.Rate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CommissionSimulator — commission brouillon vs actuelle, coût et marge par rail")
class CommissionSimulatorTest {

    private static final BigDecimal VAT = new BigDecimal("20");

    private static final ProviderCostProperties COSTS = new ProviderCostProperties(
            new BigDecimal("1.5"), new BigDecimal("2.8"), new BigDecimal("3.15"), new BigDecimal("0.25"),
            new BigDecimal("0.25"), new BigDecimal("0.10"), new BigDecimal("2.0"));

    private CommissionGridService grids;
    private CommissionGrid grid;
    private CommissionSimulator simulator;

    @BeforeEach
    void setUp() {
        grids = mock(CommissionGridService.class);
        grid = new CommissionGrid(UUID.randomUUID(), LocalDateTime.of(2026, 11, 1, 0, 0),
                List.of(tier("0", "0.07"), tier("100", "0.06"), tier("300", "0.05")),
                new EnumMap<>(CommissionGridService.defaultMinimums()));
        // Sans grille en vigueur : taux plat de 5 %, sans minimum.
        CommissionGrid flat = new CommissionGrid(null, LocalDateTime.MIN, List.of(tier("0", "0.05")), zeroMinimums());
        when(grids.flatPlatformGrid()).thenReturn(flat);
        when(grids.current()).thenReturn(Optional.empty());
        when(grids.require(grid.id())).thenReturn(grid);
        simulator = simulator(Map.of());
    }

    @Test
    @DisplayName("carte standard, net 20 € : commission 1,40, brut 21,40, coût 0,721, marge HT 0,4457")
    void simulate_card_standard_net20() {
        var row = simulate(SupportedCurrency.EUR, "20").rows().get(0);

        assertThat(row.net()).isEqualByComparingTo("20.00");
        assertThat(row.draft().commission()).isEqualByComparingTo("1.40");
        assertThat(row.draft().gross()).isEqualByComparingTo("21.40");
        assertThat(row.draft().minimumApplied()).isFalse();
        // Référence sans grille : 5 % plat.
        assertThat(row.current().commission()).isEqualByComparingTo("1.00");
        assertThat(row.current().gross()).isEqualByComparingTo("21.00");

        RailCost card = rail(row.rails(), Rail.CARD_STANDARD);
        // 21,40 × 1,5 % + 0,25 + 20 × 0,25 % + 0,10 = 0,721, affiché à 2 décimales.
        assertThat(card.providerCost()).isEqualByComparingTo("0.72");
        // Marge : 1,40 / 1,2 − 0,721, calculée sur le coût non arrondi.
        assertThat(card.marginExVat().doubleValue()).isCloseTo(0.4457, within(0.0001));
        assertThat(card.marginExVat().scale()).isEqualTo(4);

        assertThat(rail(row.rails(), Rail.CARD_PREMIUM).providerCost()).isEqualByComparingTo("1.00");
        assertThat(rail(row.rails(), Rail.CARD_INTERNATIONAL).providerCost()).isEqualByComparingTo("1.07");
    }

    @Test
    @DisplayName("mobile money XOF : pire et meilleur opérateur, clés aplaties par Spring comprises")
    void simulate_mobileMoney_xof_worstAndBest() {
        simulator = simulator(Map.of(
                "ORANGE_CIV", new Rate(new BigDecimal("2.5"), new BigDecimal("2.0")),
                // Spring peut lier ORANGE_SEN en ORANGESEN : le pays reste le suffixe.
                "MTNMOMOCIV", new Rate(new BigDecimal("1.8"), new BigDecimal("1.3")),
                "MTN_MOMO_CMR", new Rate(new BigDecimal("9"), new BigDecimal("9"))));

        var row = simulate(SupportedCurrency.XOF, "10000").rows().get(0);

        assertThat(row.draft().commission()).isEqualByComparingTo("700");
        assertThat(row.draft().gross()).isEqualByComparingTo("10700");
        // pire : 10 700 × 2,5 % + 10 000 × 2,0 % = 467,5 ; meilleur : 10 700 × 1,8 % + 10 000 × 1,3 % = 322,6.
        RailCost worst = rail(row.rails(), Rail.MOBILE_MONEY_WORST);
        RailCost best = rail(row.rails(), Rail.MOBILE_MONEY_BEST);
        assertThat(worst.providerCost()).isEqualByComparingTo("468");
        assertThat(best.providerCost()).isEqualByComparingTo("323");
        // 700 / 1,2 = 583,3333 ; marge = 583,3333 − coût non arrondi.
        assertThat(worst.marginExVat()).isEqualByComparingTo("115.8333");
        assertThat(best.marginExVat()).isEqualByComparingTo("260.7333");
        // Frais fixes en euros convertis : carte = 10 700 × 1,5 % + 0,25 × 655,957 + 10 000 × 0,25 % + 0,10 × 655,957.
        assertThat(rail(row.rails(), Rail.CARD_STANDARD).providerCost()).isEqualByComparingTo("415");
    }

    @Test
    @DisplayName("devise sans opérateur pawaPay (EUR, ou XAF sans clé XAF) : lignes mobile money absentes")
    void simulate_mobileMoney_absentWithoutOperator() {
        simulator = simulator(Map.of("ORANGE_CIV", new Rate(new BigDecimal("2.5"), new BigDecimal("2.0"))));

        assertThat(simulate(SupportedCurrency.EUR, "20").rows().get(0).rails())
                .extracting(RailCost::rail).doesNotContain(Rail.MOBILE_MONEY_WORST, Rail.MOBILE_MONEY_BEST);
        assertThat(simulate(SupportedCurrency.XAF, "10000").rows().get(0).rails())
                .extracting(RailCost::rail).doesNotContain(Rail.MOBILE_MONEY_WORST, Rail.MOBILE_MONEY_BEST);
    }

    @Test
    @DisplayName("espèces : coût de recharge par carte standard sur la commission seule")
    void simulate_cash_rechargeCost() {
        RailCost cash = rail(simulate(SupportedCurrency.EUR, "20").rows().get(0).rails(), Rail.CASH);

        // 1,40 × 1,5 % + 0,25 = 0,271 ; marge = 1,40 / 1,2 − 0,271 = 0,8957.
        assertThat(cash.providerCost()).isEqualByComparingTo("0.27");
        assertThat(cash.marginExVat()).isEqualByComparingTo("0.8957");
    }

    @Test
    @DisplayName("la réponse porte les avertissements du brouillon et le coût mensuel Connect converti")
    void simulate_returnsWarningsForDraft() {
        when(grids.warningsFor(any(CommissionGridDraft.class))).thenReturn(List.of(CommissionGridWarning.INCREASE_WITHIN_30_DAYS));

        var response = simulate(SupportedCurrency.XOF, "10000");

        assertThat(response.warnings()).containsExactly(CommissionGridWarning.INCREASE_WITHIN_30_DAYS);
        assertThat(response.connectActiveAccountMonthly()).isEqualByComparingTo("1312");
    }

    @Test
    @DisplayName("gridId : grille existante simulée, sans avertissement ; TVA absente = 20 %, TVA 0 = commission entière")
    void simulate_byGridId_andVat() {
        var request = new CommissionSimulationRequest(null, grid.id(), SupportedCurrency.EUR, List.of(new BigDecimal("20")), null, null);
        var row = simulator.simulate(request).rows().get(0);

        assertThat(simulator.simulate(request).warnings()).isEmpty();
        assertThat(rail(row.rails(), Rail.CASH).marginExVat()).isEqualByComparingTo("0.8957");

        var noVat = new CommissionSimulationRequest(null, grid.id(), SupportedCurrency.EUR, List.of(new BigDecimal("20")), BigDecimal.ZERO, null);
        // 1,40 − 0,271
        assertThat(rail(simulator.simulate(noVat).rows().get(0).rails(), Rail.CASH).marginExVat()).isEqualByComparingTo("1.1290");
    }

    @Test
    @DisplayName("la grille en vigueur sert de référence quand elle existe")
    void simulate_usesCurrentGridAsReference() {
        CommissionGrid inForce = new CommissionGrid(UUID.randomUUID(), LocalDateTime.of(2026, 1, 1, 0, 0),
                List.of(tier("0", "0.10")), new EnumMap<>(CommissionGridService.defaultMinimums()));
        when(grids.current()).thenReturn(Optional.of(inForce));

        var row = simulate(SupportedCurrency.EUR, "20").rows().get(0);

        assertThat(row.current().commission()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("effectiveFrom : la référence se lit à cette date")
    void simulate_effectiveFromReadsGridAtThatDate() {
        LocalDateTime at = LocalDateTime.of(2026, 12, 1, 0, 0);
        CommissionGrid then = new CommissionGrid(UUID.randomUUID(), LocalDateTime.of(2026, 11, 1, 0, 0),
                List.of(tier("0", "0.10")), new EnumMap<>(CommissionGridService.defaultMinimums()));
        when(grids.activeAt(at)).thenReturn(Optional.of(then));

        var request = new CommissionSimulationRequest(null, grid.id(), SupportedCurrency.EUR, List.of(new BigDecimal("20")), VAT, at);

        assertThat(simulator.simulate(request).rows().get(0).current().commission()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("ni brouillon ni gridId, ou les deux : 422 validation")
    void simulate_requiresExactlyOneGridSource() {
        var neither = new CommissionSimulationRequest(null, null, SupportedCurrency.EUR, List.of(BigDecimal.TEN), VAT, null);
        var both = new CommissionSimulationRequest(draftRequest(), grid.id(), SupportedCurrency.EUR, List.of(BigDecimal.TEN), VAT, null);

        assertThatThrownBy(() -> simulator.simulate(neither)).isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("validation");
        assertThatThrownBy(() -> simulator.simulate(both)).isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("TVA négative ou net arrondi à zéro : 422 validation")
    void simulate_rejectsNegativeVatAndZeroNet() {
        var negativeVat = new CommissionSimulationRequest(null, grid.id(), SupportedCurrency.EUR, List.of(BigDecimal.TEN), new BigDecimal("-1"), null);
        var zeroNet = new CommissionSimulationRequest(null, grid.id(), SupportedCurrency.XOF, List.of(new BigDecimal("0.4")), VAT, null);

        assertThatThrownBy(() -> simulator.simulate(negativeVat)).isInstanceOf(YadonyBusinessException.class);
        assertThatThrownBy(() -> simulator.simulate(zeroNet)).isInstanceOf(YadonyBusinessException.class);
    }

    // ── Aides ─────────────────────────────────────────────────────────────────

    private CommissionSimulator simulator(Map<String, Rate> providers) {
        return new CommissionSimulator(grids, COSTS,
                new PawapayFeeTable(new Rate(new BigDecimal("2.5"), new BigDecimal("2.0")), providers));
    }

    private CommissionSimulationResponse simulate(SupportedCurrency currency, String net) {
        return simulator.simulate(new CommissionSimulationRequest(draftRequest(), null, currency,
                List.of(new BigDecimal(net)), VAT, null));
    }

    private static CommissionGridRequest draftRequest() {
        Map<String, BigDecimal> minimums = new java.util.LinkedHashMap<>();
        CommissionGridService.defaultMinimums().forEach((c, v) -> minimums.put(c.name(), v));
        return new CommissionGridRequest(List.of(tier("0", "0.07"), tier("100", "0.06"), tier("300", "0.05")),
                minimums, null, null, null);
    }

    private static RailCost rail(List<RailCost> rails, Rail rail) {
        return rails.stream().filter(r -> r.rail() == rail).findFirst().orElseThrow();
    }

    private static CommissionTier tier(String fromEur, String rate) {
        return new CommissionTier(new BigDecimal(fromEur), new BigDecimal(rate));
    }

    private static Map<SupportedCurrency, BigDecimal> zeroMinimums() {
        Map<SupportedCurrency, BigDecimal> zero = new EnumMap<>(SupportedCurrency.class);
        for (SupportedCurrency c : SupportedCurrency.values()) {
            zero.put(c, BigDecimal.ZERO);
        }
        return zero;
    }
}
