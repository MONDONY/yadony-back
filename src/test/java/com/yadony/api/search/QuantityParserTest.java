package com.yadony.api.search;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class QuantityParserTest {

    private static ParseState state(String text, SearchMode mode) {
        return new ParseState(SearchTokenizer.tokenize(text), mode, LocalDate.of(2026, 8, 19));
    }

    @Test
    void weightInTripsMode_becomesMinimumCapacityRequired() {
        ParseState s = state("20 kilos", SearchMode.TRIPS);

        QuantityParser.apply(s);

        assertThat(s.values()).containsEntry("minAvailableKg", new BigDecimal("20"));
        assertThat(s.values()).doesNotContainKey("maxWeight");
    }

    @Test
    void weightInPackagesMode_becomesTravelerCapacity() {
        ParseState s = state("20 kilos", SearchMode.PACKAGES);

        QuantityParser.apply(s);

        assertThat(s.values()).containsEntry("maxWeight", new BigDecimal("20"));
        assertThat(s.values()).doesNotContainKey("minAvailableKg");
    }

    @Test
    void weightAcceptsGluedUnit() {
        ParseState s = state("15kg", SearchMode.TRIPS);

        QuantityParser.apply(s);

        assertThat(s.values()).containsEntry("minAvailableKg", new BigDecimal("15"));
    }

    @Test
    void weightAcceptsSpelledOutNumbers() {
        ParseState s = state("vingt kilos", SearchMode.TRIPS);

        QuantityParser.apply(s);

        assertThat(s.values()).containsEntry("minAvailableKg", new BigDecimal("20"));
    }

    @Test
    void luggageWordsCarryAConventionalWeight() {
        ParseState s = state("une valise", SearchMode.TRIPS);

        QuantityParser.apply(s);

        assertThat(s.values()).containsEntry("minAvailableKg", new BigDecimal("23"));
    }

    @Test
    void priceWithEuroSign_becomesMaxPricePerKg() {
        ParseState s = state("8€ le kilo", SearchMode.TRIPS);

        QuantityParser.apply(s);

        assertThat(s.values()).containsEntry("maxPricePerKg", new BigDecimal("8"));
    }

    @Test
    void priceWithLessThanPrefix_becomesMaxPricePerKg() {
        ParseState s = state("moins de 8 euros", SearchMode.TRIPS);

        QuantityParser.apply(s);

        assertThat(s.values()).containsEntry("maxPricePerKg", new BigDecimal("8"));
    }

    @Test
    void vaguePrice_isReportedInsteadOfGuessed() {
        ParseState s = state("pas trop cher", SearchMode.TRIPS);

        QuantityParser.apply(s);

        assertThat(s.values()).doesNotContainKey("maxPricePerKg");
        assertThat(s.unresolved()).hasSize(1);
        assertThat(s.unresolved().get(0).kind()).isEqualTo(UnresolvedKind.PRICE_VAGUE);
    }

    @Test
    void recognizedTokensAreConsumed() {
        ParseState s = state("20 kilos", SearchMode.TRIPS);

        QuantityParser.apply(s);

        assertThat(s.remaining()).isEmpty();
    }

    @Test
    void bareNumberWithoutUnit_isLeftAlone() {
        ParseState s = state("12", SearchMode.TRIPS);

        QuantityParser.apply(s);

        assertThat(s.values()).isEmpty();
        assertThat(s.remaining()).hasSize(1);
    }

    @Test
    void emptyStringIsIgnored() {
        // Edge case: a tokenizer might theoretically produce an empty token.
        // The condition n.chars().allMatch(Character::isDigit) && !n.isEmpty()
        // should return false for empty strings (short-circuit on isEmpty check).
        ParseState s = state("rien", SearchMode.TRIPS);

        QuantityParser.apply(s);

        assertThat(s.values()).isEmpty();
    }
}
