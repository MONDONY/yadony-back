package com.yadony.api.admin.dto;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionGridDraft;
import com.yadony.api.common.commission.CommissionGridWarning;
import com.yadony.api.common.commission.CommissionTier;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grille saisie dans l'onglet Tarification. Devises et avertissements arrivent en texte : un nom
 * inconnu est un 422 {@code validation} champ par champ, pas un 400 de désérialisation sans détail.
 * Les bornes et les taux sont validés par {@code CommissionGridService}, une seule fois.
 *
 * @param minimums             commission minimum par code de devise ({@code EUR}, {@code XOF}…).
 * @param effectiveFrom        date d'effet (UTC) ; absente = effet immédiat.
 * @param acknowledgedWarnings avertissements lus et acceptés ({@code INCREASE_WITHIN_30_DAYS}).
 */
public record CommissionGridRequest(List<CommissionTier> tiers, Map<String, BigDecimal> minimums,
                                    LocalDateTime effectiveFrom, String note,
                                    List<String> acknowledgedWarnings) {

    private static final String VALIDATION = "validation";

    public CommissionGridDraft toDraft() {
        Map<String, String> violations = new LinkedHashMap<>();
        Map<SupportedCurrency, BigDecimal> parsed = parseMinimums(violations);
        throwIfAny(violations);
        return new CommissionGridDraft(tiers, parsed, effectiveFrom, note);
    }

    public Set<CommissionGridWarning> acknowledged() {
        Set<CommissionGridWarning> parsed = EnumSet.noneOf(CommissionGridWarning.class);
        Map<String, String> violations = new LinkedHashMap<>();
        if (acknowledgedWarnings != null) {
            for (String name : acknowledgedWarnings) {
                try {
                    parsed.add(CommissionGridWarning.valueOf(name));
                } catch (IllegalArgumentException | NullPointerException e) {
                    violations.put("acknowledgedWarnings", "Avertissement inconnu : " + name);
                }
            }
        }
        throwIfAny(violations);
        return parsed;
    }

    private Map<SupportedCurrency, BigDecimal> parseMinimums(Map<String, String> violations) {
        Map<SupportedCurrency, BigDecimal> parsed = new EnumMap<>(SupportedCurrency.class);
        if (minimums == null) {
            return parsed;
        }
        minimums.forEach((code, value) -> {
            try {
                parsed.put(SupportedCurrency.valueOf(code), value);
            } catch (IllegalArgumentException | NullPointerException e) {
                violations.put("minimums." + code, "Devise inconnue : " + code);
            }
        });
        return parsed;
    }

    private static void throwIfAny(Map<String, String> violations) {
        if (!violations.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, VALIDATION, "Validation Error",
                    "Grille de commission invalide", Map.of("violations", violations));
        }
    }
}
