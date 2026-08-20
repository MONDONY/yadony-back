package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.ExchangeRateResponse;
import com.yadony.api.admin.dto.UpdateExchangeRateRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ExchangeRateEntity;
import com.yadony.api.payments.currency.ExchangeRateRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /** XOF et XAF : parite fixe avec l'euro, non negociable, jamais modifiable ici. */
    private static final Set<String> FIXED_PARITY_CURRENCIES = Set.of("XOF", "XAF");

    // Aucune devise supportee ne s'approche de cet ordre de grandeur (la plus elevee, XOF/XAF,
    // vaut ~656 et est de toute facon refusee ci-dessus) : au-dela, une saisie a trois zeros de
    // trop se traduirait en commissions et montants convertis totalement faux.
    private static final BigDecimal MAX_UNITS_PER_EUR = new BigDecimal("10000");

    private final ExchangeRateRepository exchangeRateRepository;
    private final AuditService auditService;
    private final CacheManager cacheManager;

    public AdminExchangeRateController(ExchangeRateRepository exchangeRateRepository,
                                       AuditService auditService,
                                       CacheManager cacheManager) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.auditService = auditService;
        this.cacheManager = cacheManager;
    }

    @GetMapping
    public List<ExchangeRateResponse> list() {
        return exchangeRateRepository.findAll().stream()
                .map(ExchangeRateResponse::from)
                .sorted(Comparator.comparing(ExchangeRateResponse::currency))
                .toList();
    }

    /**
     * L'audit_log est ecrit ICI, une seule fois par ecriture reussie — pas dans le repository,
     * qui ignore qui appelle. Le cache {@code exchange-rates} est evince APRES le save, jamais
     * avant : une eviction anticipee laisserait une fenetre ou une lecture concurrente re-peuple
     * le cache avec l'ancienne valeur juste avant le commit.
     */
    @PutMapping("/{currency}")
    @Transactional
    public ExchangeRateResponse update(@PathVariable String currency,
                                       @RequestBody UpdateExchangeRateRequest request,
                                       Authentication authentication) {
        String normalized = normalize(currency);

        if (FIXED_PARITY_CURRENCIES.contains(normalized)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "exchange-rate-fixed-parity", "Fixed Parity Currency",
                    normalized + " a une parite fixe avec l'euro (655,957), elle ne se pilote pas depuis cet ecran.",
                    Map.of("currency", normalized));
        }

        validateRate(request.unitsPerEur());

        ExchangeRateEntity entity = exchangeRateRepository.findByCurrency(normalized)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "exchange-rate-not-found", "Exchange Rate Not Found",
                        "Aucun taux de change n'existe pour la devise " + normalized));

        UUID adminId = adminId(authentication);

        entity.setUnitsPerEur(request.unitsPerEur());
        entity.setUpdatedAt(OffsetDateTime.now());
        entity.setUpdatedBy(adminId);
        ExchangeRateEntity saved = exchangeRateRepository.save(entity);

        evictCache(normalized);

        auditService.log("EXCHANGE_RATE", null, "EXCHANGE_RATE_UPDATED", adminId,
                Map.of("currency", normalized, "unitsPerEur", saved.getUnitsPerEur().toPlainString()));

        return ExchangeRateResponse.from(saved);
    }

    private void validateRate(BigDecimal unitsPerEur) {
        if (unitsPerEur == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "exchange-rate-required", "Exchange Rate Required",
                    "Le taux de change est obligatoire");
        }
        if (unitsPerEur.compareTo(BigDecimal.ZERO) <= 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "exchange-rate-not-positive", "Exchange Rate Not Positive",
                    "Le taux de change doit etre strictement positif",
                    Map.of("unitsPerEur", unitsPerEur.toPlainString()));
        }
        if (unitsPerEur.compareTo(MAX_UNITS_PER_EUR) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "exchange-rate-out-of-range", "Exchange Rate Out Of Range",
                    "Le taux de change depasse la borne maximale autorisee (" + MAX_UNITS_PER_EUR.toPlainString() + ")",
                    Map.of("unitsPerEur", unitsPerEur.toPlainString(), "max", MAX_UNITS_PER_EUR.toPlainString()));
        }
    }

    /** Evince explicitement : point d'ecriture unique, comme {@code platform-settings}. */
    private void evictCache(String currency) {
        Cache cache = cacheManager.getCache("exchange-rates");
        if (cache != null) {
            cache.evict(currency);
        }
    }

    private String normalize(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    }

    private UUID adminId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }
}
