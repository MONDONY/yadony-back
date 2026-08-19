package com.yadony.api.search;

import com.yadony.api.search.dto.ParsedFilters;
import com.yadony.api.search.dto.SearchParseResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Enchaîne les passes de reconnaissance.
 *
 * <p><strong>L'ordre n'est pas arbitraire.</strong> Chaque passe consomme les tokens
 * qu'elle reconnaît, et la résolution des villes ne travaille que sur le reliquat.
 * Remonter la passe villes ferait de « mars » une destination : les trigrammes de
 * {@code pg_trgm} matchent volontiers un nom de mois sur une ville homonyme.
 */
@Service
public class SearchQueryParser {

    private final CityLexicon cityLexicon;

    public SearchQueryParser(CityLexicon cityLexicon) {
        this.cityLexicon = cityLexicon;
    }

    public SearchParseResponse parse(String text, SearchMode mode, LocalDate today) {
        ParseState state = new ParseState(SearchTokenizer.tokenize(text), mode, today);

        QuantityParser.apply(state);        // 1. poids et prix
        DateExpressionParser.apply(state);  // 2. dates
        FlagParser.apply(state);            // 3. drapeaux et transport
        ContentTypeParser.apply(state);     // 4. catégories de contenu
        cityLexicon.apply(state);           // 5. villes, sur le reliquat uniquement

        return new SearchParseResponse(
                toFilters(state.values()),
                state.recognized(),
                state.unresolved(),
                state.ignoredWords());
    }

    private static ParsedFilters toFilters(Map<String, Object> v) {
        return new ParsedFilters(
                str(v, "departureCity"),
                str(v, "arrivalCity"),
                date(v, "departureDateFrom"),
                date(v, "departureDateTo"),
                num(v, "minAvailableKg"),
                num(v, "maxWeight"),
                num(v, "maxPricePerKg"),
                num(v, "minRating"),
                bool(v, "weekendOnly"),
                bool(v, "urgent"),
                bool(v, "kiloProOnly"),
                bool(v, "kycVerifiedOnly"),
                str(v, "transportMode"),
                str(v, "contentType"));
    }

    private static String str(Map<String, Object> v, String k) {
        Object o = v.get(k);
        return o instanceof String s ? s : null;
    }

    private static LocalDate date(Map<String, Object> v, String k) {
        Object o = v.get(k);
        return o instanceof LocalDate d ? d : null;
    }

    private static BigDecimal num(Map<String, Object> v, String k) {
        Object o = v.get(k);
        return o instanceof BigDecimal b ? b : null;
    }

    private static Boolean bool(Map<String, Object> v, String k) {
        Object o = v.get(k);
        return o instanceof Boolean b ? b : null;
    }
}
