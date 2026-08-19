package com.yadony.api.search;

import com.yadony.api.search.dto.RecognizedField;
import com.yadony.api.search.dto.UnresolvedItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParseStateTest {

    private final LocalDate today = LocalDate.of(2026, 8, 19);

    private ParseState newState(String text) {
        return new ParseState(SearchTokenizer.tokenize(text), SearchMode.TRIPS, today);
    }

    @Test
    void constructor_exposesModeAndToday() {
        ParseState state = newState("20 kilos à Bamako");

        assertThat(state.mode()).isEqualTo(SearchMode.TRIPS);
        assertThat(state.today()).isEqualTo(today);
    }

    @Test
    void allTokens_returnsEveryTokenRegardlessOfConsumption() {
        ParseState state = newState("20 kilos à Bamako");

        assertThat(state.allTokens()).extracting(Token::raw)
                .containsExactly("20", "kilos", "à", "Bamako");
    }

    @Test
    void remaining_beforeAnyConsumption_returnsAllTokens() {
        ParseState state = newState("20 kilos à Bamako");

        assertThat(state.remaining()).isEqualTo(state.allTokens());
    }

    @Test
    void consume_removesTheGivenRangeFromRemaining() {
        ParseState state = newState("20 kilos à Bamako");

        state.consume(0, 2);

        assertThat(state.remaining()).extracting(Token::raw)
                .containsExactly("à", "Bamako");
        assertThat(state.isConsumed(0)).isTrue();
        assertThat(state.isConsumed(1)).isTrue();
        assertThat(state.isConsumed(2)).isFalse();
        assertThat(state.isConsumed(3)).isFalse();
    }

    @Test
    void consume_clampsToIndexExclusiveBeyondTokenCount_withoutThrowing() {
        ParseState state = newState("20 kilos");

        state.consume(0, 100);

        assertThat(state.remaining()).isEmpty();
    }

    @Test
    void put_recordsValueAndRecognizedFieldWithSpanAndConfidence() {
        ParseState state = newState("20 kilos à Bamako");
        Token bamako = state.allTokens().get(3);

        state.put("arrivalCity", "Bamako", bamako, 0.9);

        assertThat(state.values()).containsEntry("arrivalCity", "Bamako");
        assertThat(state.recognized()).hasSize(1);
        RecognizedField field = state.recognized().get(0);
        assertThat(field.field()).isEqualTo("arrivalCity");
        assertThat(field.value()).isEqualTo("Bamako");
        assertThat(field.span()).containsExactly(bamako.start(), bamako.end());
        assertThat(field.confidence()).isEqualTo(0.9);
    }

    @Test
    void put_calledTwice_accumulatesBothValuesInInsertionOrder() {
        ParseState state = newState("20 kilos à Bamako");
        Token twenty = state.allTokens().get(0);
        Token bamako = state.allTokens().get(3);

        state.put("minAvailableKg", "20", twenty, 1.0);
        state.put("arrivalCity", "Bamako", bamako, 0.9);

        assertThat(state.values().keySet()).containsExactly("minAvailableKg", "arrivalCity");
        assertThat(state.recognized()).extracting(RecognizedField::field)
                .containsExactly("minAvailableKg", "arrivalCity");
    }

    @Test
    void addUnresolved_recordsKindPhraseAndOptions() {
        ParseState state = newState("Kedougou");

        state.addUnresolved(UnresolvedKind.CITY_UNKNOWN, "kedougou", List.of());

        assertThat(state.unresolved()).hasSize(1);
        UnresolvedItem item = state.unresolved().get(0);
        assertThat(item.kind()).isEqualTo(UnresolvedKind.CITY_UNKNOWN);
        assertThat(item.phrase()).isEqualTo("kedougou");
        assertThat(item.options()).isEmpty();
    }

    @Test
    void ignoredWords_returnsRawTextOfTokensNoPassClaimed() {
        ParseState state = newState("20 kilos à Bamako");
        state.consume(0, 2);

        assertThat(state.ignoredWords()).containsExactly("à", "Bamako");
    }

    @Test
    void onEmptyTokenList_remainingAndIgnoredWordsAreEmpty() {
        ParseState state = new ParseState(List.of(), SearchMode.PACKAGES, today);

        assertThat(state.remaining()).isEmpty();
        assertThat(state.ignoredWords()).isEmpty();
    }
}
