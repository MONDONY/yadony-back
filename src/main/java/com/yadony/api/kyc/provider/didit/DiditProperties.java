package com.yadony.api.kyc.provider.didit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration Didit. Aucune valeur en dur : tout vient de variables d'environnement.
 *
 * @param baseUrl       racine de l'API de verification
 * @param apiKey        en-tete {@code x-api-key} de tous les appels
 * @param workflowId    workflow KYC configure au Dashboard (document + liveness, sans NFC :
 *                      la lecture de puce exige un SDK natif, hors de portee d'une webview)
 * @param webhookSecret {@code secret_shared_key} de la destination webhook, montre une seule
 *                      fois a la creation de celle-ci
 */
@ConfigurationProperties("yadony.didit")
public record DiditProperties(String baseUrl, String apiKey, String workflowId, String webhookSecret) {}
