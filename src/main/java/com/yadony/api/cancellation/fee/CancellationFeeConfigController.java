package com.yadony.api.cancellation.fee;

import com.yadony.api.cancellation.dto.CancellationFeeConfigResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * État public des frais d'annulation, sous {@code /config} (public sans authentification, comme tout
 * {@code /config/**}, {@code SecurityConfig}). Lecture seule de la configuration : l'app choisit ses textes selon
 * {@code active}. La décision « allumé » est celle de {@link CancellationFeeEligibility#active()}, jamais recalculée ici.
 */
@RestController
@RequestMapping("/config")
public class CancellationFeeConfigController {

    private final CancellationFeeProperties properties;
    private final CancellationFeeEligibility eligibility;

    public CancellationFeeConfigController(CancellationFeeProperties properties,
                                           CancellationFeeEligibility eligibility) {
        this.properties = properties;
        this.eligibility = eligibility;
    }

    @GetMapping("/cancellation-fee")
    public ResponseEntity<CancellationFeeConfigResponse> get() {
        boolean active = eligibility.active();
        return ResponseEntity.ok(new CancellationFeeConfigResponse(
                active,
                active ? properties.effectiveFrom() : null,
                properties.capEur(),
                properties.tiers().stream()
                        .map(t -> new CancellationFeeConfigResponse.Tier(t.minDaysBefore(), t.rate()))
                        .toList()));
    }
}
