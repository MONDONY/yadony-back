package com.yadony.api.admin.dto;

/**
 * Etat du bandeau « Nouvelle version disponible » lu dans Firebase Remote Config.
 * {@code minSupportedBuild} (verrou de version minimale) est en lecture seule.
 */
public record AppUpdateResponse(int recommendedBuild, int minSupportedBuild) {
}
