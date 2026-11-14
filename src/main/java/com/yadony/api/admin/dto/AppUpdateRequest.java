package com.yadony.api.admin.dto;

/**
 * Nouveau build recommande. Les bornes (0 a 100000) sont verifiees par
 * {@code AppUpdateService}, qui renvoie le 422 RFC 7807 en francais.
 */
public record AppUpdateRequest(Integer recommendedBuild) {
}
