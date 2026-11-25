package com.yadony.api.matching;

import com.yadony.api.matching.dto.PriceSuggestionResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prix conseillés pour l'étape « Prix » de la publication d'un trajet. Lecture seule, sans
 * donnée personnelle : seules des médianes sortent, jamais un trajet ni un voyageur. Les
 * invités n'y accèdent pas (règle finale de SecurityConfig) : seul un compte publie un trajet.
 */
@RestController
@Validated
@RequestMapping("/announcements/price-suggestions")
public class PriceSuggestionController {

    private final PriceSuggestionService service;

    public PriceSuggestionController(PriceSuggestionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PriceSuggestionResponse suggest(
            @RequestParam @NotBlank @Size(max = 100) String departureCity,
            @RequestParam @NotBlank @Size(max = 100) String arrivalCity,
            @RequestParam(defaultValue = "EUR") @Pattern(regexp = "[A-Za-z]{3}") String currency) {
        return service.suggest(departureCity, arrivalCity, currency);
    }
}
