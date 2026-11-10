package com.yadony.api.dac7;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.dto.TaxInfoRequest;
import com.yadony.api.dac7.dto.TaxInfoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Informations fiscales DAC7 du voyageur connecté. Hors de la liste blanche des invités ({@code SecurityConfig}) :
 * un {@code ROLE_GUEST} reçoit 403.
 *
 * <p>Interrupteur {@code yadony.dac7.enabled} (éteint par défaut) : éteint, les deux endpoints répondent 404
 * {@code dac7-disabled}, comme l'assistant éteint ({@code assistant-disabled}) : l'app lit un 404 comme « fonction
 * absente » et n'affiche pas l'écran.
 */
@RestController
@RequestMapping("/travelers/me/tax-info")
public class TravelerTaxInfoController {

    private final TravelerTaxInfoService service;
    private final Dac7Properties properties;

    public TravelerTaxInfoController(TravelerTaxInfoService service, Dac7Properties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping
    @PreAuthorize("hasRole('TRAVELER')")
    public TaxInfoResponse get(@AuthenticationPrincipal String firebaseUid) {
        requireEnabled();
        return service.get(requireUid(firebaseUid));
    }

    @PutMapping
    @PreAuthorize("hasRole('TRAVELER')")
    public TaxInfoResponse set(@AuthenticationPrincipal String firebaseUid, @RequestBody TaxInfoRequest request) {
        requireEnabled();
        return service.set(requireUid(firebaseUid), request);
    }

    private void requireEnabled() {
        if (!properties.enabledOrDefault()) {
            throw new YadonyBusinessException(HttpStatus.NOT_FOUND, "dac7-disabled", "Not Found",
                    "Les informations fiscales ne sont pas disponibles.");
        }
    }

    private static String requireUid(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return firebaseUid;
    }
}
