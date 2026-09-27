package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.ExchangeRateResponse;
import com.yadony.api.admin.dto.UpdateExchangeRateRequest;
import com.yadony.api.payments.currency.ExchangeRateRepository;
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
 * <p>La lecture reste ouverte a tout ROLE_ADMIN. L'ecriture (PUT d'un taux, synchronisation
 * BCE manuelle) exige en plus {@code CONFIG_MANAGE}, comme les parametres plateforme : un taux
 * est une donnee de reference unique dont la modification affecte tout affichage multidevise
 * et toute commission convertie. Sans cette authority, le profil SUPPORT pouvait la changer.
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
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @PutMapping("/{currency}")
    public ExchangeRateResponse update(@PathVariable String currency,
                                       @RequestBody UpdateExchangeRateRequest request,
                                       Authentication authentication) {
        return ExchangeRateResponse.from(updateService.apply(
                currency, request.unitsPerEur(), adminId(authentication), "EXCHANGE_RATE_UPDATED"));
    }

    private UUID adminId(Authentication authentication) {
        return AdminPrincipal.requireAdminId(authentication);
    }

    /**
     * Déclenchement manuel de la synchronisation BCE (même chemin que le cron de
     * 07 h 00 UTC) : diagnostic immédiat après déploiement ou incident, sans attendre
     * le prochain passage planifié. Mêmes garde-fous que la synchronisation
     * automatique (parité fixe, variation maximale, alertes).
     *
     * @return nombre de devises effectivement mises à jour.
     */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")
    @PostMapping("/sync")
    public java.util.Map<String, Integer> syncNow(Authentication authentication) {
        // L'admin est l'acteur de l'audit : sans lui, une synchro manuelle ne se distinguait
        // pas du cron (actorId null).
        return java.util.Map.of("updated", syncService.syncAll(adminId(authentication)));
    }
}
