package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.ExchangeRateResponse;
import com.yadony.api.admin.dto.UpdateExchangeRateRequest;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ExchangeRateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Tache 11 — pilotage des taux de {@code exchange_rates} depuis le back-office.
 *
 * <p>Reserve a ROLE_ADMIN, sans authority dediee : contrairement aux ecrans Lot D
 * ({@code CONFIG_MANAGE}, {@code PROMO_MANAGE}...), un taux de change n'est pas une feature a
 * activer/desactiver selon le profil admin, c'est une donnee de reference unique dont la
 * modification affecte tout affichage multidevise en cours.
 *
 * <p>{@code XOF} et {@code XAF} sont en lecture SEULE : leur parite avec l'euro est fixe
 * (655,957 CFA/EUR, un traite monetaire, pas un taux de marche) — les modifier depuis cet
 * ecran casserait cette parite sans justification economique.
 */
@RestController
@RequestMapping("/admin/exchange-rates")
@PreAuthorize("hasRole('ADMIN')")
public class AdminExchangeRateController {

    private final ExchangeRateRepository exchangeRateRepository;
    private final com.yadony.api.payments.currency.ExchangeRateUpdateService updateService;
    private final com.yadony.api.payments.currency.ExchangeRateSyncService syncService;

    public AdminExchangeRateController(ExchangeRateRepository exchangeRateRepository,
                                       com.yadony.api.payments.currency.ExchangeRateUpdateService updateService,
                                       com.yadony.api.payments.currency.ExchangeRateSyncService syncService) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.updateService = updateService;
        this.syncService = syncService;
    }

    @GetMapping
    public List<ExchangeRateResponse> list() {
        return exchangeRateRepository.findAll().stream()
                .map(ExchangeRateResponse::from)
                .sorted(Comparator.comparing(ExchangeRateResponse::currency))
                .toList();
    }

    /**
     * Toute la séquence (validation, save, éviction, repivot, audit) vit dans
     * {@code ExchangeRateUpdateService}, partagée avec la synchronisation BCE : deux
     * copies divergeraient. Ici ne restent que l'identité de l'admin et le mapping HTTP.
     */
    @PutMapping("/{currency}")
    public ExchangeRateResponse update(@PathVariable String currency,
                                       @RequestBody UpdateExchangeRateRequest request,
                                       Authentication authentication) {
        return ExchangeRateResponse.from(updateService.apply(
                currency, request.unitsPerEur(), adminId(authentication), "EXCHANGE_RATE_UPDATED"));
    }

    private UUID adminId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }

    /**
     * Déclenchement manuel de la synchronisation BCE (même chemin que le cron de
     * 07 h 00 UTC) : diagnostic immédiat après déploiement ou incident, sans attendre
     * le prochain passage planifié. Mêmes garde-fous que la synchronisation
     * automatique (parité fixe, variation maximale, alertes).
     *
     * @return nombre de devises effectivement mises à jour.
     */
    @PostMapping("/sync")
    public java.util.Map<String, Integer> syncNow() {
        return java.util.Map.of("updated", syncService.syncAll());
    }
}
