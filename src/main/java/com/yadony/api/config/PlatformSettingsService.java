package com.yadony.api.config;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Source unique des quatre parametres plateforme.
 *
 * <p>La table fait autorite ; les properties ne servent plus que de filet de securite
 * quand une ligne manque (fenetre entre la migration et l'amorcage, base restauree
 * partiellement). Sans ce filet, {@code /config/commission-rate} — appele par
 * l'application mobile deployee — repondrait 500 au lieu de sa valeur habituelle.
 */
@Service
public class PlatformSettingsService {

    /** 30 % : au-dela, la commission cesserait d'etre une commission. */
    private static final BigDecimal MAX_COMMISSION_RATE = new BigDecimal("0.30");
    /** 500 € : plafond de la valeur declaree d'un colis, deja applique cote metier. */
    private static final BigDecimal MAX_REIMBURSEMENT_CAP = new BigDecimal("500");
    private static final int MIN_URGENCY_DAYS = 1;
    private static final int MAX_URGENCY_DAYS = 30;

    private final PlatformSettingRepository repository;
    private final PlatformSettingsCache cache;
    private final AuditService auditService;
    private final YadonyConfigProperties config;
    private final boolean smsEnabledProperty;

    public PlatformSettingsService(PlatformSettingRepository repository,
                                   PlatformSettingsCache cache,
                                   AuditService auditService,
                                   YadonyConfigProperties config,
                                   @Value("${app.sms.enabled:false}") boolean smsEnabledProperty) {
        this.repository = repository;
        this.cache = cache;
        this.auditService = auditService;
        this.config = config;
        this.smsEnabledProperty = smsEnabledProperty;
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    public BigDecimal commissionRate() {
        String raw = cache.all().get(PlatformSettingKey.COMMISSION_RATE.key());
        return raw == null ? config.commission().rate() : new BigDecimal(raw);
    }

    public int urgencyThresholdDays() {
        String raw = cache.all().get(PlatformSettingKey.URGENCY_THRESHOLD_DAYS.key());
        return raw == null ? config.urgency().thresholdDays() : Integer.parseInt(raw);
    }

    public BigDecimal reimbursementCapEur() {
        String raw = cache.all().get(PlatformSettingKey.REIMBURSEMENT_CAP_EUR.key());
        return raw == null ? config.reimbursement().maxAmountEur() : new BigDecimal(raw);
    }

    public boolean smsEnabled() {
        String raw = cache.all().get(PlatformSettingKey.SMS_ENABLED.key());
        return raw == null ? smsEnabledProperty : Boolean.parseBoolean(raw);
    }

    @Transactional(readOnly = true)
    public PlatformSettingsSnapshot snapshot() {
        List<PlatformSettingEntity> rows = repository.findAll();
        PlatformSettingEntity mostRecent = rows.stream()
                .filter(row -> row.getUpdatedBy() != null)
                .max((a, b) -> a.getUpdatedAt().compareTo(b.getUpdatedAt()))
                .orElse(null);
        return new PlatformSettingsSnapshot(
                commissionRate(), urgencyThresholdDays(), reimbursementCapEur(), smsEnabled(),
                mostRecent == null ? null : mostRecent.getUpdatedAt(),
                mostRecent == null ? null : mostRecent.getUpdatedBy());
    }

    /**
     * Vue cle par cle pour le back-office, dans l'ordre de declaration de l'enum — ordre
     * d'affichage stable, la liste n'etant triee nulle part cote interface.
     *
     * <p>La liste est pilotee par l'enum, pas par la table : une cle dont la ligne manque
     * (base restauree d'avant la migration, amorcage non encore joue) est servie avec sa
     * valeur effective plutot qu'omise — l'ecran resterait sinon silencieusement incomplet.
     */
    @Transactional(readOnly = true)
    public List<PlatformSettingView> listByKey() {
        Map<String, PlatformSettingEntity> rows = repository.findAll().stream()
                .collect(Collectors.toMap(PlatformSettingEntity::getSettingKey, Function.identity()));
        return Arrays.stream(PlatformSettingKey.values())
                .map(key -> {
                    PlatformSettingEntity row = rows.get(key.key());
                    if (row == null) {
                        return new PlatformSettingView(key, effectiveValue(key), null, null);
                    }
                    // La date n'est exposee QUE s'il y a un auteur : BaseEntity horodate des
                    // l'amorcage, l'afficher seule ferait passer le seed pour une modification.
                    boolean edited = row.getUpdatedBy() != null;
                    return new PlatformSettingView(key, row.getSettingValue(),
                            edited ? row.getUpdatedAt() : null, row.getUpdatedBy());
                })
                .toList();
    }

    /** Valeur servie quand la ligne manque : la meme que celle lue par le contrat public. */
    private String effectiveValue(PlatformSettingKey key) {
        return switch (key) {
            case COMMISSION_RATE -> commissionRate().toPlainString();
            case URGENCY_THRESHOLD_DAYS -> String.valueOf(urgencyThresholdDays());
            case REIMBURSEMENT_CAP_EUR -> reimbursementCapEur().toPlainString();
            case SMS_ENABLED -> String.valueOf(smsEnabled());
        };
    }

    // ── Ecriture ─────────────────────────────────────────────────────────────

    /**
     * Applique les seules cles presentes dans {@code changes}. Une valeur identique a
     * l'existante n'ecrit rien et n'audite rien : audit_log est immuable, le polluer de
     * non-changements le rendrait illisible.
     */
    @Transactional
    public PlatformSettingsSnapshot update(Map<PlatformSettingKey, String> changes, UUID adminId) {
        boolean touched = false;
        for (Map.Entry<PlatformSettingKey, String> entry : changes.entrySet()) {
            PlatformSettingKey key = entry.getKey();
            String newValue = normalize(key, entry.getValue());

            // La ligne manquante est creee avec la valeur EFFECTIVE COURANTE, jamais avec la
            // valeur neuve : sinon oldValue.equals(newValue) serait vrai, le continue plus bas
            // sauterait l'attribution, l'audit ET l'eviction de cache. Le reglage changerait
            // alors sans laisser la moindre trace — et audit_log etant immuable, elle ne
            // pourrait jamais etre ajoutee apres coup.
            PlatformSettingEntity row = repository.findBySettingKey(key.key())
                    .orElseGet(() -> repository.save(
                            new PlatformSettingEntity(key.key(), effectiveValue(key), key.type())));
            String oldValue = row.getSettingValue();
            if (oldValue.equals(newValue)) {
                continue;
            }

            row.setSettingValue(newValue);
            row.setUpdatedBy(adminId);
            repository.save(row);
            touched = true;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("key", key.key());
            payload.put("oldValue", oldValue);
            payload.put("newValue", newValue);
            auditService.log("platform_setting", row.getId(),
                    "PLATFORM_SETTING_CHANGED", adminId, payload);
        }
        if (touched) {
            cache.evict();
        }
        return snapshot();
    }

    /**
     * Ecrit UNE cle et renvoie la ligne resultante — la forme attendue par le back-office,
     * qui enregistre reglage par reglage.
     *
     * <p>Delegue a {@link #update(Map, UUID)} plutot que de reimplementer l'ecriture : bornes,
     * audit et invalidation de cache restent en un seul endroit.
     */
    @Transactional
    public PlatformSettingView updateOne(PlatformSettingKey key, String value, UUID adminId) {
        update(Map.of(key, value), adminId);
        return listByKey().stream()
                .filter(view -> view.key() == key)
                .findFirst()
                // Inatteignable : listByKey parcourt toutes les valeurs de l'enum.
                .orElseThrow(() -> new IllegalStateException("Reglage absent de la vue : " + key));
    }

    /** Valide les bornes ET normalise la forme, pour que « 0.10 » et « 0.1 » ne divergent pas. */
    private String normalize(PlatformSettingKey key, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw invalid("La valeur de " + key.key() + " est obligatoire");
        }
        String value = rawValue.trim();
        return switch (key) {
            case COMMISSION_RATE -> {
                BigDecimal rate = parseDecimal(key, value);
                if (rate.signum() < 0 || rate.compareTo(MAX_COMMISSION_RATE) > 0) {
                    throw invalid("Le taux de commission doit etre compris entre 0 et 30 %");
                }
                yield rate.toPlainString();
            }
            case REIMBURSEMENT_CAP_EUR -> {
                BigDecimal cap = parseDecimal(key, value);
                if (cap.signum() <= 0 || cap.compareTo(MAX_REIMBURSEMENT_CAP) > 0) {
                    throw invalid("Le plafond de remboursement doit etre compris entre 0 et 500 euros");
                }
                yield cap.toPlainString();
            }
            case URGENCY_THRESHOLD_DAYS -> {
                int days;
                try {
                    days = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw invalid("Le seuil d'urgence doit etre un nombre entier de jours");
                }
                if (days < MIN_URGENCY_DAYS || days > MAX_URGENCY_DAYS) {
                    throw invalid("Le seuil d'urgence doit etre compris entre 1 et 30 jours");
                }
                yield String.valueOf(days);
            }
            case SMS_ENABLED -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw invalid("L'activation des SMS attend true ou false");
                }
                yield String.valueOf(Boolean.parseBoolean(value));
            }
        };
    }

    private BigDecimal parseDecimal(PlatformSettingKey key, String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw invalid("La valeur de " + key.key() + " doit etre un nombre");
        }
    }

    private YadonyBusinessException invalid(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "platform-setting-invalid", "Unprocessable Entity", detail);
    }
}
