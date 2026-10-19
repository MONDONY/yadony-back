package com.yadony.api.payments.pawapay;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Indication de validation par opérateur ({@code yadony.pawapay.validation-hints}, voir
 * application.yml). Tout opérateur absent de la table, ou dont la valeur n'est pas un
 * {@link ValidationHint}, retombe sur {@link ValidationHint#GENERIC} : une valeur invalide ne
 * doit jamais empêcher le démarrage.
 *
 * <p>Les clés sont normalisées (majuscules, caractères non alphanumériques retirés) des deux
 * côtés, comme {@code PawapayFeeTable} : Spring peut aplatir {@code ORANGE_CIV} en
 * {@code ORANGECIV}.
 */
@Component
public class PawapayValidationHints {

    private static final Logger log = LoggerFactory.getLogger(PawapayValidationHints.class);

    private final Map<String, ValidationHint> byProvider;

    public PawapayValidationHints(@Value("#{${yadony.pawapay.validation-hints:{:}}}") Map<String, String> config) {
        Map<String, ValidationHint> parsed = new HashMap<>();
        if (config != null) {
            config.forEach((provider, raw) -> {
                if (provider == null) {
                    return;
                }
                parsed.put(normalize(provider), parse(provider, raw));
            });
        }
        this.byProvider = Map.copyOf(parsed);
    }

    /** Indication configurée pour le code opérateur pawaPay ({@code ORANGE_CIV}…), {@link ValidationHint#GENERIC} sinon. */
    public ValidationHint forProvider(String providerCode) {
        if (providerCode == null) {
            return ValidationHint.GENERIC;
        }
        return byProvider.getOrDefault(normalize(providerCode), ValidationHint.GENERIC);
    }

    private static ValidationHint parse(String provider, String raw) {
        if (raw != null) {
            try {
                return ValidationHint.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // tombe sur GENERIC ci-dessous
            }
        }
        log.warn("yadony.pawapay.validation-hints : valeur invalide '{}' pour {}, GENERIC utilisé", raw, provider);
        return ValidationHint.GENERIC;
    }

    private static String normalize(String key) {
        return key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
