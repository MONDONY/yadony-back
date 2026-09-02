package com.yadony.api.payments.currency;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Point d'écriture UNIQUE d'un taux de {@code exchange_rates} : validation, save,
 * éviction du cache, publication de {@link ExchangeRateChangedEvent} (recalcul des
 * pivots EUR côté {@code matching}) et audit — dans une seule transaction.
 *
 * <p>Deux entrées s'y branchent : le back-office ({@code AdminExchangeRateController})
 * et la synchronisation BCE quotidienne ({@code ExchangeRateSyncService}). Extraire
 * plutôt que dupliquer : deux copies de la séquence save→evict→repivot divergeraient,
 * et un chemin qui oublierait le repivot laisserait le fil trier sur l'ancien taux.
 *
 * <p>L'ordre est significatif : le cache {@code exchange-rates} est évincé APRÈS le
 * save, jamais avant — une éviction anticipée laisserait une fenêtre où une lecture
 * concurrente re-peuple le cache avec l'ancienne valeur juste avant le commit.
 */
@Service
public class ExchangeRateUpdateService {

    /** XOF et XAF : parité fixe avec l'euro (655,957, un traité) — jamais modifiables. */
    public static final Set<String> FIXED_PARITY_CURRENCIES = Set.of("XOF", "XAF");

    // Aucune devise supportée ne s'approche de cet ordre de grandeur (la plus élevée,
    // XOF/XAF, vaut ~656 et est de toute façon refusée) : au-delà, une saisie à trois
    // zéros de trop se traduirait en commissions et montants convertis totalement faux.
    public static final BigDecimal MAX_UNITS_PER_EUR = new BigDecimal("10000");

    private final ExchangeRateRepository exchangeRateRepository;
    private final AuditService auditService;
    private final CacheManager cacheManager;
    private final ApplicationEventPublisher eventPublisher;

    public ExchangeRateUpdateService(ExchangeRateRepository exchangeRateRepository,
                                     AuditService auditService,
                                     CacheManager cacheManager,
                                     ApplicationEventPublisher eventPublisher) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.auditService = auditService;
        this.cacheManager = cacheManager;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Applique un nouveau taux à une devise flottante existante.
     *
     * @param currency    code devise, casse libre (normalisé en majuscules ici)
     * @param unitsPerEur nouveau taux (unités pour un euro), borné à (0, 10 000]
     * @param actorId     admin à l'origine du changement, {@code null} pour la
     *                    synchronisation automatique
     * @param auditAction action portée dans l'audit_log — {@code EXCHANGE_RATE_UPDATED}
     *                    (admin) ou {@code EXCHANGE_RATE_SYNCED} (cron BCE), pour que
     *                    l'historique distingue une main humaine d'un robot
     * @return l'entité sauvegardée
     */
    @Transactional
    public ExchangeRateEntity apply(String currency, BigDecimal unitsPerEur,
                                    UUID actorId, String auditAction) {
        String normalized = normalize(currency);

        if (FIXED_PARITY_CURRENCIES.contains(normalized)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "exchange-rate-fixed-parity", "Fixed Parity Currency",
                    normalized + " a une parite fixe avec l'euro (655,957), elle ne se pilote pas depuis cet ecran.",
                    Map.of("currency", normalized));
        }

        validateRate(unitsPerEur);

        ExchangeRateEntity entity = exchangeRateRepository.findByCurrency(normalized)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "exchange-rate-not-found", "Exchange Rate Not Found",
                        "Aucun taux de change n'existe pour la devise " + normalized));

        entity.setUnitsPerEur(unitsPerEur);
        entity.setUpdatedAt(OffsetDateTime.now());
        entity.setUpdatedBy(actorId);
        // saveAndFlush, pas save : le listener synchrone du pivot execute un bulk
        // JPQL clearAutomatically dans cette meme transaction — une ecriture encore
        // en attente au moment du clear serait perdue sans bruit.
        ExchangeRateEntity saved = exchangeRateRepository.saveAndFlush(entity);

        evictCache(normalized);

        // Le pivot EUR des annonces est une dérivée du taux : listener synchrone,
        // même transaction (cf. ExchangeRateChangedEvent).
        eventPublisher.publishEvent(new ExchangeRateChangedEvent(normalized, saved.getUnitsPerEur()));

        auditService.log("EXCHANGE_RATE", null, auditAction, actorId,
                Map.of("currency", normalized, "unitsPerEur", saved.getUnitsPerEur().toPlainString()));

        return saved;
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

    /** Évince explicitement : point d'écriture unique, comme {@code platform-settings}. */
    private void evictCache(String currency) {
        Cache cache = cacheManager.getCache("exchange-rates");
        if (cache != null) {
            cache.evict(currency);
        }
    }

    private String normalize(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    }
}
