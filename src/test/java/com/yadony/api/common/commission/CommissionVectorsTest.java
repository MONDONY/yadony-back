package com.yadony.api.common.commission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.promo.PromoDiscount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rejoue les vecteurs de référence de {@code commission/vectors.json} (spec §4.6). Le même
 * fichier est recopié dans dony_app : un vecteur qui passe ici doit passer dans le portage Dart.
 */
@DisplayName("CommissionCalculator — vecteurs de référence partagés avec l'app")
class CommissionVectorsTest {

    private static final JsonNode ROOT = load();
    private static final CommissionGrid GRID = grid(ROOT.get("grid"));

    static Stream<Arguments> vectors() {
        List<Arguments> args = new ArrayList<>();
        ROOT.get("vectors").forEach(v -> args.add(Arguments.of(v.get("case").asText(), v)));
        return args.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void vector(String name, JsonNode v) {
        SupportedCurrency currency = SupportedCurrency.valueOf(v.get("currency").asText());
        CommissionReductions reductions = reductions(v.get("reductions"));
        JsonNode expected = v.get("expected");

        if (v.has("net")) {
            CommissionResult result = CommissionCalculator.compute(
                    new BigDecimal(v.get("net").asText()), currency, GRID, reductions);
            assertThat(result.commission()).isEqualByComparingTo(expected.get("commission").asText());
            assertThat(result.commission().scale()).isEqualTo(currency.minorUnit());
            assertThat(result.gross()).isEqualByComparingTo(expected.get("gross").asText());
            assertThat(result.minimumApplied()).isEqualTo(expected.get("minimumApplied").asBoolean());
            assertThat(result.promoEffective()).isEqualTo(expected.get("promoEffective").asBoolean());
            return;
        }

        BigDecimal gross = new BigDecimal(v.get("gross").asText());
        if (expected.has("error")) {
            assertThatThrownBy(() -> CommissionCalculator.netForGross(gross, currency, GRID, reductions))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus().value()).isEqualTo(422);
                        assertThat(ex.getErrorCode()).isEqualTo(expected.get("error").asText());
                    });
            return;
        }
        BigDecimal net = CommissionCalculator.netForGross(gross, currency, GRID, reductions);
        assertThat(net).isEqualByComparingTo(expected.get("net").asText());
        if (expected.has("commission")) {
            assertThat(CommissionCalculator.compute(net, currency, GRID, reductions).commission())
                    .isEqualByComparingTo(expected.get("commission").asText());
        }
    }

    private static CommissionReductions reductions(JsonNode r) {
        PromoDiscount promo = null;
        if (r.has("promoPoints")) {
            promo = PromoDiscount.points(new BigDecimal(r.get("promoPoints").asText()));
        } else if (r.has("promoPricePercent")) {
            promo = PromoDiscount.pricePercent(new BigDecimal(r.get("promoPricePercent").asText()));
        }
        return new CommissionReductions(decimal(r, "overrideRate"), promo, decimal(r, "voucherFactor"));
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return node.has(field) ? new BigDecimal(node.get(field).asText()) : null;
    }

    private static CommissionGrid grid(JsonNode g) {
        List<CommissionTier> tiers = new ArrayList<>();
        g.get("tiers").forEach(t -> tiers.add(new CommissionTier(
                new BigDecimal(t.get("fromEur").asText()), new BigDecimal(t.get("rate").asText()))));
        Map<SupportedCurrency, BigDecimal> minimums = new EnumMap<>(SupportedCurrency.class);
        g.get("minimums").fields().forEachRemaining(e ->
                minimums.put(SupportedCurrency.valueOf(e.getKey()), new BigDecimal(e.getValue().asText())));
        return new CommissionGrid(UUID.randomUUID(), LocalDateTime.of(2026, 11, 1, 0, 0), tiers, minimums);
    }

    private static JsonNode load() {
        try (InputStream in = CommissionVectorsTest.class.getClassLoader()
                .getResourceAsStream("commission/vectors.json")) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
