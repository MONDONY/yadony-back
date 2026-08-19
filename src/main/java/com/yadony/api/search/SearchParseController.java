package com.yadony.api.search;

import com.yadony.api.search.dto.SearchParseRequest;
import com.yadony.api.search.dto.SearchParseResponse;
import jakarta.validation.Valid;
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

    private final SearchQueryParser parser;

    public SearchParseController(SearchQueryParser parser) {
        this.parser = parser;
    }

    @PostMapping("/parse")
    public ResponseEntity<SearchParseResponse> parse(@Valid @RequestBody SearchParseRequest request) {
        LocalDate today = request.today() != null ? request.today() : LocalDate.now();
        return ResponseEntity.ok(parser.parse(request.text(), request.mode(), today));
    }
}
