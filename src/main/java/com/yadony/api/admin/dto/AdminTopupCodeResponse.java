package com.yadony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * {@code code} : texte en clair, renseigné SEULEMENT dans la réponse de création (le code est
 * stocké haché, il ne peut plus être relu). {@code codeHint} : fin du code, toujours présente.
 */
public record AdminTopupCodeResponse(UUID id, String code, String codeHint, BigDecimal amount, String currency,
                                     int maxRedemptions, int redeemedCount, int remaining,
                                     LocalDateTime expiresAt, String displayStatus, String note,
                                     LocalDateTime createdAt, UUID createdBy, String createdByEmail,
                                     LocalDateTime revokedAt) {}
