package com.yadony.api.common.commission;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Grilles de commission datées (spec 2026-10-18 §5.1 et §5.2) : lecture de la grille en vigueur pour
 * tous les rails, programmation validée, avertissement de hausse, annulation, historique.
 *
 * <p><b>Cache</b> : le cache {@code commission-grids} contient la liste de TOUTES les grilles non
 * annulées. {@link #activeAt} et {@link #current} filtrent cette liste sur l'horloge à chaque appel :
 * une grille programmée prend donc effet à l'instant dit, sans attendre l'expiration du cache. Chaque
 * écriture le vide APRÈS le commit (vidé avant, un appel concurrent le repeuplerait avec l'ancien
 * état) ; sa TTL de 30 s n'est qu'un filet si plusieurs instances tournent.
 *
 * <p>Le service n'écrit jamais {@code effective_from} d'une grille existante : seule l'annulation d'une
 * grille programmée ({@code deleted_at}) modifie une ligne. Le trigger de V331 le garantit aussi en base.
 */
@Service
public class CommissionGridService {

    static final String CACHE = "commission-grids";
    private static final String CACHE_KEY = "all";

    private static final String AUDIT_ENTITY = "COMMISSION_GRID";
    private static final String VALIDATION = "validation";

    /** Une date d'effet un peu passée (délai de saisie, horloge du navigateur) est ramenée à maintenant. */
    private static final Duration PAST_TOLERANCE = Duration.ofMinutes(5);
    /** Une hausse qui prend effet avant cette échéance doit être acquittée. */
    private static final Duration WARNING_HORIZON = Duration.ofDays(30);
    private static final int MAX_TIERS = 10;
    private static final BigDecimal MAX_RATE = new BigDecimal("0.30");
    /** Garde-fou contre une faute de frappe : un minimum ne dépasse pas l'équivalent de 5 €. */
    private static final BigDecimal MAX_MINIMUM_EUR = new BigDecimal("5");
    private static final int MAX_NOTE_LENGTH = 500;
    /** Échantillon de nets en euros comparés pour détecter une hausse : 1 € à 1 000 €, pas de 1 €. */
    private static final int SAMPLE_MAX_EUR = 1000;

    private final CommissionGridRepository repository;
    private final AuditService auditService;
    private final PlatformSettingsService settings;
    private final CacheManager cacheManager;
    private final Clock clock;

    @Autowired
    public CommissionGridService(CommissionGridRepository repository, AuditService auditService,
                                 PlatformSettingsService settings, CacheManager cacheManager) {
        this(repository, auditService, settings, cacheManager, Clock.systemUTC());
    }

    CommissionGridService(CommissionGridRepository repository, AuditService auditService,
                          PlatformSettingsService settings, CacheManager cacheManager, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.settings = settings;
        this.cacheManager = cacheManager;
        this.clock = clock;
    }

    // ── Lecture ──────────────────────────────────────────────────────────────────

    /** Grille en vigueur maintenant ; vide tant que le propriétaire n'en a programmé aucune. */
    public Optional<CommissionGrid> current() {
        return activeAt(now());
    }

    /** Prochaine grille programmée (pas encore en vigueur), s'il y en a une. */
    public Optional<CommissionGrid> scheduled() {
        LocalDateTime now = now();
        return allGrids().stream()
                .filter(g -> g.effectiveFrom().isAfter(now))
                .min((a, b) -> a.effectiveFrom().compareTo(b.effectiveFrom()));
    }

    /** La plus récente grille dont {@code effective_from <= t}. */
    public Optional<CommissionGrid> activeAt(LocalDateTime t) {
        return latestAtOrBefore(allGrids(), t);
    }

    /**
     * Grille par identifiant (celle figée sur un bid, un fil ou une demande).
     *
     * @throws YadonyBusinessException 404 {@code commission-grid-not-found}
     */
    public CommissionGrid require(UUID id) {
        return allGrids().stream().filter(g -> g.id().equals(id)).findFirst()
                .or(() -> repository.findById(id).map(CommissionGridEntity::toGrid))
                .orElseThrow(CommissionGridService::notFound);
    }

    /** Historique complet, grilles annulées comprises, la plus récente d'abord ({@code pageable} sans tri). */
    @Transactional(readOnly = true)
    public Page<CommissionGridHistoryEntry> history(Pageable pageable) {
        return repository.findHistory(pageable).map(e ->
                new CommissionGridHistoryEntry(e.toGrid(), e.getDeletedAt(), e.getCreatedBy(), e.getNote(), e.getCreatedAt()));
    }

    // ── Avertissement de hausse ──────────────────────────────────────────────────

    /**
     * Avertissements que lèverait la programmation de {@code draft}.
     *
     * @throws YadonyBusinessException 422 si le brouillon est invalide
     */
    public List<CommissionGridWarning> warningsFor(CommissionGridDraft draft) {
        LocalDateTime now = now();
        LocalDateTime effectiveFrom = validatedEffectiveFrom(draft, now);
        return warnings(draft, effectiveFrom, loadAll(), now);
    }

    // ── Écriture ─────────────────────────────────────────────────────────────────

    /**
     * Programme une grille et remplace celle qui était programmée (au plus une à la fois).
     *
     * @param acknowledged avertissements que l'administrateur a lus et acceptés
     * @throws YadonyBusinessException 422 {@code validation} (violations par champ),
     *         {@code commission-grid-effective-in-past} ou {@code commission-grid-warning-not-acknowledged}
     */
    @Transactional
    public CommissionGrid schedule(CommissionGridDraft draft, Set<CommissionGridWarning> acknowledged, UUID adminId) {
        LocalDateTime now = now();
        LocalDateTime effectiveFrom = validatedEffectiveFrom(draft, now);

        List<CommissionGridWarning> warnings = warnings(draft, effectiveFrom, loadAll(), now);
        Set<CommissionGridWarning> accepted = acknowledged == null ? Set.of() : acknowledged;
        if (!accepted.containsAll(warnings)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "commission-grid-warning-not-acknowledged", "Commission Grid Warning Not Acknowledged",
                    "La grille augmente la commission : acquittez l'avertissement pour la programmer.",
                    Map.of("warnings", warnings.stream().map(Enum::name).toList()));
        }

        String note = normalizedNote(draft.note());
        CommissionGridEntity saved = repository.save(
                new CommissionGridEntity(effectiveFrom, draft.tiers(), draft.minimums(), note, adminId));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tiers", CommissionGridEntity.serializeTiers(draft.tiers()));
        payload.put("minimums", CommissionGridEntity.serializeMinimums(draft.minimums()));
        payload.put("effectiveFrom", effectiveFrom.toString());
        payload.put("acknowledgedWarnings", warnings.stream().map(Enum::name).toList());
        payload.put("note", note);
        auditService.log(AUDIT_ENTITY, saved.getId(), "COMMISSION_GRID_SCHEDULED", adminId, payload);

        for (CommissionGridEntity replaced : repository.findByEffectiveFromAfter(now)) {
            if (!replaced.getId().equals(saved.getId())) {
                cancelEntity(replaced, now, adminId, Map.of("reason", "REPLACED", "replacedBy", saved.getId()));
            }
        }
        evictAfterCommit();
        return saved.toGrid();
    }

    /**
     * Annule (suppression logique) une grille programmée.
     *
     * @throws YadonyBusinessException 404 {@code commission-grid-not-found} ; 409
     *         {@code commission-grid-already-effective} si la grille est déjà en vigueur
     */
    @Transactional
    public void cancel(UUID id, UUID adminId) {
        CommissionGridEntity entity = repository.findById(id).orElseThrow(CommissionGridService::notFound);
        LocalDateTime now = now();
        if (!entity.getEffectiveFrom().isAfter(now)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "commission-grid-already-effective",
                    "Commission Grid Already Effective",
                    "Une grille déjà en vigueur ne peut pas être annulée : programmez-en une nouvelle.");
        }
        cancelEntity(entity, now, adminId, Map.of("reason", "CANCELLED"));
        evictAfterCommit();
    }

    /** Minimums proposés par défaut : 1 € et son équivalent dans chaque devise, arrondi au 0,05 supérieur. */
    public static Map<SupportedCurrency, BigDecimal> defaultMinimums() {
        Map<SupportedCurrency, BigDecimal> defaults = new EnumMap<>(SupportedCurrency.class);
        defaults.put(SupportedCurrency.EUR, new BigDecimal("1.00"));
        defaults.put(SupportedCurrency.XOF, new BigDecimal("650"));
        defaults.put(SupportedCurrency.XAF, new BigDecimal("650"));
        defaults.put(SupportedCurrency.USD, new BigDecimal("1.10"));
        defaults.put(SupportedCurrency.CAD, new BigDecimal("1.50"));
        defaults.put(SupportedCurrency.GBP, new BigDecimal("0.90"));
        defaults.put(SupportedCurrency.CHF, new BigDecimal("0.95"));
        return defaults;
    }

    // ── Avertissement : détail ───────────────────────────────────────────────────

    private List<CommissionGridWarning> warnings(CommissionGridDraft draft, LocalDateTime effectiveFrom,
                                                 List<CommissionGrid> all, LocalDateTime now) {
        if (!effectiveFrom.isBefore(now.plus(WARNING_HORIZON))) {
            return List.of();
        }
        CommissionGrid candidate = new CommissionGrid(null, effectiveFrom, draft.tiers(), draft.minimums());
        // Les grilles programmées sont ignorées : la programmation du brouillon remplace celle qui l'est déjà.
        List<CommissionGrid> inForce = all.stream().filter(g -> !g.effectiveFrom().isAfter(now)).toList();
        CommissionGrid reference = latestAtOrBefore(inForce, effectiveFrom.minusNanos(1))
                .orElseGet(this::flatPlatformGrid);
        return isIncrease(candidate, reference) ? List.of(CommissionGridWarning.INCREASE_WITHIN_30_DAYS) : List.of();
    }

    /** Référence sans grille en vigueur : le taux plat actuel de la plateforme, sans minimum. */
    private CommissionGrid flatPlatformGrid() {
        Map<SupportedCurrency, BigDecimal> noMinimum = new EnumMap<>(SupportedCurrency.class);
        for (SupportedCurrency currency : SupportedCurrency.values()) {
            noMinimum.put(currency, BigDecimal.ZERO);
        }
        return new CommissionGrid(null, LocalDateTime.MIN,
                List.of(new CommissionTier(BigDecimal.ZERO, settings.commissionRate())), noMinimum);
    }

    /**
     * Une hausse : un minimum plus élevé dans au moins une devise, ou une commission plus élevée pour
     * au moins un net en euros de 1 € à 1 000 € (pas de 1 €), calculée sans réduction.
     */
    private static boolean isIncrease(CommissionGrid candidate, CommissionGrid reference) {
        for (SupportedCurrency currency : SupportedCurrency.values()) {
            if (candidate.minimum(currency).compareTo(reference.minimum(currency)) > 0) {
                return true;
            }
        }
        for (int eur = 1; eur <= SAMPLE_MAX_EUR; eur++) {
            BigDecimal net = BigDecimal.valueOf(eur);
            BigDecimal proposed = CommissionCalculator
                    .compute(net, SupportedCurrency.EUR, candidate, CommissionReductions.NONE).commission();
            BigDecimal current = CommissionCalculator
                    .compute(net, SupportedCurrency.EUR, reference, CommissionReductions.NONE).commission();
            if (proposed.compareTo(current) > 0) {
                return true;
            }
        }
        return false;
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    /**
     * Valide le brouillon et renvoie sa date d'effet normalisée : {@code null} ou à moins de 5 minutes
     * dans le passé = maintenant ; le futur est conservé. Arrondie à la microseconde, précision de la colonne.
     */
    private static LocalDateTime validatedEffectiveFrom(CommissionGridDraft draft, LocalDateTime now) {
        Map<String, String> violations = new LinkedHashMap<>();
        LocalDateTime requested = draft.effectiveFrom();
        boolean inPast = requested != null && requested.isBefore(now.minus(PAST_TOLERANCE));
        if (inPast) {
            violations.put("effectiveFrom", "La date d'effet ne peut pas être dans le passé.");
        }
        validateTiers(draft.tiers(), violations);
        validateMinimums(draft.minimums(), violations);
        if (draft.note() != null && draft.note().strip().length() > MAX_NOTE_LENGTH) {
            violations.put("note", "La note ne peut pas dépasser " + MAX_NOTE_LENGTH + " caractères.");
        }
        if (!violations.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    inPast ? "commission-grid-effective-in-past" : VALIDATION, "Validation Error",
                    "Grille de commission invalide", Map.of("violations", violations));
        }
        LocalDateTime effective = requested == null || !requested.isAfter(now) ? now : requested;
        return effective.truncatedTo(ChronoUnit.MICROS);
    }

    private static void validateTiers(List<CommissionTier> tiers, Map<String, String> violations) {
        if (tiers == null || tiers.isEmpty() || tiers.size() > MAX_TIERS) {
            violations.put("tiers", "Une grille compte de 1 à " + MAX_TIERS + " tranches.");
            return;
        }
        BigDecimal previousBound = null;
        for (int i = 0; i < tiers.size(); i++) {
            CommissionTier tier = tiers.get(i);
            if (tier == null || tier.fromEur() == null || tier.rate() == null) {
                violations.put("tiers[" + i + "]", "Chaque tranche porte une borne et un taux.");
                previousBound = null;
                continue;
            }
            if (i == 0 && tier.fromEur().signum() != 0) {
                violations.put("tiers[0].fromEur", "La première borne vaut 0.");
            } else if (i > 0 && previousBound != null && tier.fromEur().compareTo(previousBound) <= 0) {
                violations.put("tiers[" + i + "].fromEur", "Les bornes doivent être strictement croissantes.");
            }
            if (tier.rate().signum() < 0 || tier.rate().compareTo(MAX_RATE) > 0) {
                violations.put("tiers[" + i + "].rate", "Le taux doit être compris entre 0 % et 30 %.");
            }
            previousBound = tier.fromEur();
        }
    }

    private static void validateMinimums(Map<SupportedCurrency, BigDecimal> minimums, Map<String, String> violations) {
        for (SupportedCurrency currency : SupportedCurrency.values()) {
            BigDecimal minimum = minimums == null ? null : minimums.get(currency);
            String field = "minimums." + currency.name();
            if (minimum == null) {
                violations.put(field, "Le minimum est requis pour " + currency.name() + ".");
            } else if (minimum.signum() < 0) {
                violations.put(field, "Le minimum ne peut pas être négatif.");
            } else if (minimum.compareTo(MAX_MINIMUM_EUR.multiply(currency.unitsPerEur())) > 0) {
                violations.put(field, "Le minimum ne peut pas dépasser l'équivalent de 5 €.");
            }
        }
    }

    private static String normalizedNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return note.strip();
    }

    // ── Cache et horloge ─────────────────────────────────────────────────────────

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    private List<CommissionGrid> allGrids() {
        Cache cache = Objects.requireNonNull(cacheManager.getCache(CACHE), "cache " + CACHE);
        return cache.get(CACHE_KEY, this::loadAll);
    }

    /** Lecture directe en base, la plus récente d'abord. */
    private List<CommissionGrid> loadAll() {
        List<CommissionGrid> grids = new ArrayList<>();
        for (CommissionGridEntity entity : repository.findAllByOrderByEffectiveFromDesc()) {
            grids.add(entity.toGrid());
        }
        return List.copyOf(grids);
    }

    private static Optional<CommissionGrid> latestAtOrBefore(List<CommissionGrid> grids, LocalDateTime t) {
        return grids.stream()
                .filter(g -> !g.effectiveFrom().isAfter(t))
                .max((a, b) -> a.effectiveFrom().compareTo(b.effectiveFrom()));
    }

    /**
     * Le cache n'est vidé qu'après le commit : vidé avant, un appel concurrent le repeuplerait avec
     * l'ancien état. Sans transaction active, il l'est tout de suite.
     */
    private void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict();
                }
            });
        } else {
            evict();
        }
    }

    private void evict() {
        Cache cache = cacheManager.getCache(CACHE);
        if (cache != null) {
            cache.clear();
        }
    }

    private void cancelEntity(CommissionGridEntity entity, LocalDateTime now, UUID adminId, Map<String, Object> extra) {
        entity.setDeletedAt(now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("effectiveFrom", entity.getEffectiveFrom().toString());
        payload.putAll(extra);
        auditService.log(AUDIT_ENTITY, entity.getId(), "COMMISSION_GRID_CANCELLED", adminId, payload);
    }

    private static YadonyBusinessException notFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "commission-grid-not-found",
                "Commission Grid Not Found", "Grille de commission introuvable");
    }
}
