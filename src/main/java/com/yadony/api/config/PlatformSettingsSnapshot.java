package com.yadony.api.config;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Etat complet des parametres plateforme, avec la trace de la derniere modification.
 * {@code updatedBy} est null tant qu'aucun administrateur n'a rien change (valeurs amorcees).
 */
public record PlatformSettingsSnapshot(
        BigDecimal commissionRate,
        int urgencyThresholdDays,
        BigDecimal reimbursementCapEur,
        boolean smsEnabled,
        LocalDateTime updatedAt,
        UUID updatedBy) {
}
