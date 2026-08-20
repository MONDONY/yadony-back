package com.yadony.api.search;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.search.dto.SearchParseRequest;
import com.yadony.api.search.dto.SearchParseResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Traduit une phrase libre en filtres de recherche.
 *
 * <p>Aucune entrée {@code audit_log} : une recherche n'est pas une action métier
 * significative. Aucune donnée n'est persistée, la phrase n'est pas conservée.
 */
@PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
@RestController
@RequestMapping("/search")
public class SearchParseController {

    /**
     * {@code today} est fourni par le client (tests déterministes) sans aucune
     * borne côté serveur. Une valeur pathologique (proche de {@code LocalDate.MIN}
     * ou {@code LocalDate.MAX}) fait déborder l'arithmétique de date de
     * {@code DateExpressionParser} (ex: {@code plusMonths}) avec une
     * {@code DateTimeException} non rattrapée : 500 + {@code Sentry.captureException}
     * à chaque appel — un utilisateur authentifié peut épuiser le quota Sentry en
     * boucle. La plage 1900-2200 couvre toute valeur de test plausible (voir
     * {@code SearchParseControllerIT}, qui utilise 1999) tout en excluant les
     * valeurs extrêmes responsables du débordement.
     */
    private static final int TODAY_MIN_YEAR = 1900;
    private static final int TODAY_MAX_YEAR = 2200;

    private final SearchQueryParser parser;

    public SearchParseController(SearchQueryParser parser) {
        this.parser = parser;
    }

    @PostMapping("/parse")
    public ResponseEntity<SearchParseResponse> parse(@Valid @RequestBody SearchParseRequest request) {
        LocalDate today = request.today() != null ? request.today() : LocalDate.now();
        if (today.getYear() < TODAY_MIN_YEAR || today.getYear() > TODAY_MAX_YEAR) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "search-today-out-of-range",
                    "Date de référence hors plage",
                    "Le paramètre 'today' doit être compris entre les années %d et %d."
                            .formatted(TODAY_MIN_YEAR, TODAY_MAX_YEAR));
        }
        return ResponseEntity.ok(parser.parse(request.text(), request.mode(), today));
    }
}
