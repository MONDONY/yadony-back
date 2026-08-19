package com.yadony.api.search;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DateExpressionParserTest {

    /** Toutes les assertions sont ancrées à cette date pour rester déterministes. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 19);

    private static ParseState state(String text) {
        return new ParseState(SearchTokenizer.tokenize(text), SearchMode.TRIPS, TODAY);
    }

    @Test
    void namedMonthResolvesToItsNextOccurrence() {
        // Nous sommes en août 2026 : mars est derrière nous, donc mars 2027.
        ParseState s = state("mars");

        DateExpressionParser.apply(s);

        assertThat(s.values()).containsEntry("departureDateFrom", LocalDate.of(2027, 3, 1));
        assertThat(s.values()).containsEntry("departureDateTo", LocalDate.of(2027, 3, 31));
    }

    @Test
    void namedMonthLaterThisYearStaysThisYear() {
        ParseState s = state("décembre");

        DateExpressionParser.apply(s);

        assertThat(s.values()).containsEntry("departureDateFrom", LocalDate.of(2026, 12, 1));
        assertThat(s.values()).containsEntry("departureDateTo", LocalDate.of(2026, 12, 31));
    }

    @Test
    void currentMonthStaysThisYear() {
        ParseState s = state("août");

        DateExpressionParser.apply(s);

        assertThat(s.values()).containsEntry("departureDateFrom", LocalDate.of(2026, 8, 1));
        assertThat(s.values()).containsEntry("departureDateTo", LocalDate.of(2026, 8, 31));
    }

    @Test
    void dayAndMonthResolveToASingleDay() {
        ParseState s = state("le 12 mars");

        DateExpressionParser.apply(s);

        assertThat(s.values()).containsEntry("departureDateFrom", LocalDate.of(2027, 3, 12));
        assertThat(s.values()).containsEntry("departureDateTo", LocalDate.of(2027, 3, 12));
    }

    @Test
    void tomorrowResolvesToASingleDay() {
        ParseState s = state("demain");

        DateExpressionParser.apply(s);

        assertThat(s.values()).containsEntry("departureDateFrom", LocalDate.of(2026, 8, 20));
        assertThat(s.values()).containsEntry("departureDateTo", LocalDate.of(2026, 8, 20));
    }

    @Test
    void thisWeekendSetsTheWeekendFlagWithoutPinningDates() {
        ParseState s = state("ce week-end");

        DateExpressionParser.apply(s);

        assertThat(s.values()).containsEntry("weekendOnly", Boolean.TRUE);
    }

    @Test
    void nextMonthResolvesToTheFollowingCalendarMonth() {
        ParseState s = state("le mois prochain");

        DateExpressionParser.apply(s);

        assertThat(s.values()).containsEntry("departureDateFrom", LocalDate.of(2026, 9, 1));
        assertThat(s.values()).containsEntry("departureDateTo", LocalDate.of(2026, 9, 30));
    }

    @Test
    void vagueDate_isReportedInsteadOfGuessed() {
        ParseState s = state("bientôt");

        DateExpressionParser.apply(s);

        assertThat(s.values()).doesNotContainKey("departureDateFrom");
        assertThat(s.unresolved()).hasSize(1);
        assertThat(s.unresolved().get(0).kind()).isEqualTo(UnresolvedKind.DATE_VAGUE);
    }

    @Test
    void monthTokenIsConsumedSoTheCityPassNeverSeesIt() {
        // Le piège : « mars » ressemble à une ville pour pg_trgm.
        ParseState s = state("mars");

        DateExpressionParser.apply(s);

        assertThat(s.remaining()).isEmpty();
    }
}
