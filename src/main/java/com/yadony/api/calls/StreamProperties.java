package com.yadony.api.calls;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Stream Video. Activé par CALLS_ENABLED ; clé publique + secret serveur (jamais côté app). */
@ConfigurationProperties("yadony.calls")
public record StreamProperties(boolean enabled, String baseUrl, String apiKey, String apiSecret,
                               String callType, int deliveryGraceDays) {

    public boolean configured() {
        return enabled && apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();
    }
}
