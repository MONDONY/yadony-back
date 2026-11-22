package com.yadony.api.payments.wallet.fees;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PawapayFeeTableTest {

    @Test
    void defaultRates_apply() {
        PawapayFeeTable table = new PawapayFeeTable(new PawapayFeeTable.Rate(BigDecimal.ONE, BigDecimal.ONE),
                Map.of());

        assertThat(table.fee(null, new BigDecimal("10000"), "XOF")).isEqualByComparingTo("200");
    }

    @Test
    void providerOverride_wins() {
        PawapayFeeTable table = new PawapayFeeTable(new PawapayFeeTable.Rate(BigDecimal.ONE, BigDecimal.ONE),
                Map.of("ORANGE_CIV", new PawapayFeeTable.Rate(new BigDecimal("1.5"), BigDecimal.ONE)));

        assertThat(table.fee("ORANGE_CIV", new BigDecimal("10000"), "XOF")).isEqualByComparingTo("250");
    }

    @Test
    void unknownProvider_fallsBackToDefaults() {
        PawapayFeeTable table = new PawapayFeeTable(new PawapayFeeTable.Rate(BigDecimal.ONE, BigDecimal.ONE),
                Map.of("ORANGE_CIV", new PawapayFeeTable.Rate(new BigDecimal("1.5"), BigDecimal.ONE)));

        assertThat(table.fee("WAVE_SEN", new BigDecimal("10000"), "XOF")).isEqualByComparingTo("200");
    }

    @Test
    void nullDefaults_fallsBackToHardcodedPawapayGrid() {
        PawapayFeeTable table = new PawapayFeeTable(null, null);

        // 2,5 % + 2,0 %
        assertThat(table.fee(null, new BigDecimal("10000"), "XOF")).isEqualByComparingTo("450");
    }

    /**
     * Liaison réelle depuis un YAML, comme au démarrage de l'application : Spring
     * "aplatit" les clés de map non crochetées et peut en retirer les séparateurs
     * (ORANGE_CIV -> ORANGECIV). La recherche de {@link PawapayFeeTable#fee} doit
     * retrouver la surcharge malgré cette transformation.
     */
    private static PawapayFeeTable bindFromYaml(String yaml) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("test",
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        return new Binder(ConfigurationPropertySources.from(propertySources))
                .bind("yadony.pawapay.fees", PawapayFeeTable.class)
                .get();
    }

    @Test
    void bindingFromYaml_orangeCivOverrideIsFoundDespiteKeyNormalization() throws IOException {
        PawapayFeeTable table = bindFromYaml("""
                yadony:
                  pawapay:
                    fees:
                      default:
                        deposit-percent: 1.0
                        refund-percent: 1.0
                      providers:
                        ORANGE_CIV:
                          deposit-percent: 1.5
                          refund-percent: 1.0
                """);

        assertThat(table.fee("ORANGE_CIV", new BigDecimal("10000"), "XOF")).isEqualByComparingTo("250");
    }

    @Test
    void bindingFromYaml_defaultsApplyWithoutAnyProviderOverride() throws IOException {
        PawapayFeeTable table = bindFromYaml("""
                yadony:
                  pawapay:
                    fees:
                      default:
                        deposit-percent: 1.0
                        refund-percent: 1.0
                      providers: {}
                """);

        assertThat(table.fee("ORANGE_CIV", new BigDecimal("10000"), "XOF")).isEqualByComparingTo("200");
    }

    /** Le vrai application.yml, placeholders résolus sur leur valeur par défaut. */
    private static PawapayFeeTable bindFromApplicationYml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        return new Binder(ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(propertySources))
                .bind("yadony.pawapay.fees", PawapayFeeTable.class)
                .get();
    }

    @Test
    void applicationYml_orangeCiv_remboursementTientDansCeQuePawapayAEncaisse() throws IOException {
        // Prod 18/10 : recharge Orange CI de 500 XOF, 487 crédités sur le wallet pawaPay
        // (2,5 %). L'ancien barème (1 % + 1 %) renvoyait 490 : PAWAPAY_WALLET_OUT_OF_FUNDS.
        PawapayFeeTable table = bindFromApplicationYml();
        BigDecimal deposit = new BigDecimal("500");
        BigDecimal net = deposit.subtract(table.fee("ORANGE_CIV", deposit, "XOF"));

        assertThat(net).isEqualByComparingTo("477");
        BigDecimal credited = new BigDecimal("487");
        BigDecimal refundFee = net.multiply(new BigDecimal("0.02"));
        assertThat(net.add(refundFee)).isLessThanOrEqualTo(credited);
    }

    @Test
    void applicationYml_chaqueOperateurDeLaGrille_estLuEtDefautPourLesAutres() throws IOException {
        PawapayFeeTable table = bindFromApplicationYml();
        BigDecimal amount = new BigDecimal("10000");

        assertThat(table.fee("MTN_MOMO_CIV", amount, "XOF")).isEqualByComparingTo("310");
        assertThat(table.fee("WAVE_SEN", amount, "XOF")).isEqualByComparingTo("400");
        assertThat(table.fee("MTN_MOMO_CMR", amount, "XAF")).isEqualByComparingTo("305");
        assertThat(table.fee("ORANGE_BFA", amount, "XOF")).isEqualByComparingTo("530");
        // Opérateur absent de la grille (ex. FREE_SEN) : défaut 2,5 % + 2,0 %.
        assertThat(table.fee("FREE_SEN", amount, "XOF")).isEqualByComparingTo("450");
    }

    // ── Remboursement partiel d'un dépôt (frais d'annulation, rail mobile money) ──────────────────────────────

    private static final PawapayFeeTable PARTIAL = new PawapayFeeTable(
            new PawapayFeeTable.Rate(new BigDecimal("2.5"), new BigDecimal("2.0")),
            Map.of("ORANGE_SEN", new PawapayFeeTable.Rate(new BigDecimal("1.5"), new BigDecimal("1.0"))));

    @Test
    void partialRefundFee_depositOnTheDeposited_refundOnTheRefunded_roundedOnce() {
        // 65 600 × 1,5 % = 984 ; 45 922 × 1 % = 459,22 ; total 1 443,22 → 1 443 F (échelle 0).
        assertThat(PARTIAL.partialRefundFee("ORANGE_SEN", new BigDecimal("65600"), new BigDecimal("45922"), "XOF"))
                .isEqualByComparingTo("1443")
                .hasScaleOf(0);
    }

    @Test
    void partialRefundFee_operatorWithoutOverride_usesTheDefault() {
        // 50 000 × 2,5 % = 1 250 ; 35 000 × 2 % = 700.
        assertThat(PARTIAL.partialRefundFee("MTN_MOMO_CIV", new BigDecimal("50000"), new BigDecimal("35000"), "XOF"))
                .isEqualByComparingTo("1950");
    }

    @Test
    void partialRefundFee_eurosAtTwoDecimals() {
        // 100 × 2,5 % = 2,50 ; 70 × 2 % = 1,40.
        assertThat(PARTIAL.partialRefundFee(null, new BigDecimal("100.00"), new BigDecimal("70.00"), "EUR"))
                .isEqualByComparingTo("3.90")
                .hasScaleOf(2);
    }

    @Test
    void partialRefundFee_wholeRefund_equalsTheWholeDepositFee() {
        BigDecimal amount = new BigDecimal("20000");

        assertThat(PARTIAL.partialRefundFee("ORANGE_SEN", amount, amount, "XOF"))
                .isEqualByComparingTo(PARTIAL.fee("ORANGE_SEN", amount, "XOF"));
    }
}
