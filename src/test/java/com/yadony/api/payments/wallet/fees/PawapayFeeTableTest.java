package com.yadony.api.payments.wallet.fees;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
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
    void nullDefaults_fallsBackToHardcodedOnePercentOnePercent() {
        PawapayFeeTable table = new PawapayFeeTable(null, null);

        assertThat(table.fee(null, new BigDecimal("10000"), "XOF")).isEqualByComparingTo("200");
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
}
