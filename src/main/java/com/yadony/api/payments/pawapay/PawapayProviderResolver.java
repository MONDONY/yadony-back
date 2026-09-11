package com.yadony.api.payments.pawapay;

import com.yadony.api.common.Msisdn;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Ce que pawaPay sait faire d'un numéro pour un type d'opération : opérateur prédit,
 * configuration active de cet opérateur, numéro normalisé, pays alpha-2. La séquence est la
 * même pour activer un compte de versement (PAYOUT) et pour initier un dépôt (DEPOSIT) ; elle
 * n'existe qu'ici, pour qu'une garde ajoutée d'un côté ne manque jamais de l'autre.
 *
 * <p>Deux familles d'échec, jamais confondues :
 * <ul>
 *   <li>pawaPay ne répond pas ou répond une donnée inexploitable (panne réseau, 5xx, numéro
 *       prédit hors bornes) — ce n'est pas la faute de l'utilisateur :
 *       {@link PawapayErrors#providerUnavailable(String, String, Exception)}, 502 ;</li>
 *   <li>le numéro est reconnu mais inexploitable pour cette opération
 *       ({@link UnsupportedNumberException}) — l'appelant en fait son 422 métier, avec le
 *       libellé de SON contexte (portefeuille du voyageur, colis de l'expéditeur).</li>
 * </ul>
 */
@Component
public class PawapayProviderResolver {

    public enum Reason {
        /** Aucun opérateur mobile money reconnu pour ce numéro. */
        NO_PROVIDER,
        /** L'opérateur existe mais ne permet pas cette opération (deposit ou payout) pour le moment. */
        OPERATION_CLOSED,
        /** L'opérateur travaille dans une autre devise que celle attendue. */
        CURRENCY_MISMATCH,
        /** Alpha-3 renvoyé par pawaPay absent de la table ISO du JDK — {@code pawapay_operations.country} est NOT NULL. */
        COUNTRY_UNKNOWN,
        /** Opérateur demandé explicitement mais absent du catalogue de ce numéro (inconnu, fermé, autre pays ou autre devise). */
        PROVIDER_NOT_AVAILABLE
    }

    /** Numéro reconnu mais inexploitable pour cette opération. */
    public static final class UnsupportedNumberException extends RuntimeException {
        private final Reason reason;
        private final String provider;
        private final String providerCurrency;

        UnsupportedNumberException(Reason reason, String provider, String providerCurrency) {
            super(reason.name());
            this.reason = reason;
            this.provider = provider;
            this.providerCurrency = providerCurrency;
        }

        public Reason reason() { return reason; }
        public String providerLabel() { return PawapayProviders.label(provider); }
        public String providerCurrency() { return providerCurrency; }
    }

    public record Resolved(String provider, String countryAlpha2, String msisdn, PawapayProviderConfig config) {
        public String providerLabel() { return PawapayProviders.label(provider); }
    }

    /**
     * Tous les opérateurs utilisables par un numéro pour une opération et une devise : ceux du pays
     * prédit, ouverts à l'opération, dans la devise attendue, l'opérateur prédit en tête. {@code detected}
     * est nul quand l'opérateur prédit n'est pas dans {@code options} (fermé pour l'opération, autre
     * devise) alors que d'autres réseaux du pays restent possibles.
     */
    public record Catalogue(String countryAlpha2, String currency, String msisdn, String detected,
                            List<PawapayProviderConfig> options) {
        public Optional<PawapayProviderConfig> option(String provider) {
            if (provider == null) {
                return Optional.empty();
            }
            return options.stream().filter(o -> o.provider().equalsIgnoreCase(provider.trim())).findFirst();
        }
    }

    private final PawapayClient client;

    public PawapayProviderResolver(PawapayClient client) {
        this.client = client;
    }

    /**
     * @param rawMsisdn        numéro déjà normalisé ou tel que reçu (Firebase, saisie) : pawaPay
     *                         rend sa propre forme, qui prime
     * @param kind             {@code DEPOSIT} (le numéro paie) ou {@code PAYOUT} (le numéro reçoit)
     * @param expectedCurrency devise que l'opération exige (colis, ou portefeuille du voyageur)
     * @param context          libellé métier pour le journal en cas d'indisponibilité pawaPay
     *                         (ex. « l'activation mobile money de <userId> ») — jamais un numéro
     */
    public Resolved resolve(String rawMsisdn, PawapayOperationKind kind, String expectedCurrency, String context) {
        PawapayProviderPrediction prediction = predict(rawMsisdn, context);
        Map<String, PawapayProviderConfig> configuration = configuration(context);
        PawapayProviderConfig conf = configuration.get(prediction.provider());
        if (!isOperational(conf, kind)) {
            throw new UnsupportedNumberException(Reason.OPERATION_CLOSED, prediction.provider(), null);
        }
        if (!conf.currency().equalsIgnoreCase(expectedCurrency)) {
            throw new UnsupportedNumberException(Reason.CURRENCY_MISMATCH, prediction.provider(), conf.currency());
        }
        String msisdn = normalizedMsisdn(prediction, rawMsisdn, context);
        String country = PawapayCountries.toAlpha2(prediction.countryAlpha3());
        if (country == null) {
            throw new UnsupportedNumberException(Reason.COUNTRY_UNKNOWN, prediction.provider(), null);
        }
        return new Resolved(prediction.provider(), country, msisdn, conf);
    }

    /**
     * Comme {@link #resolve(String, PawapayOperationKind, String, String)}, mais avec un opérateur
     * choisi par l'utilisateur : il doit figurer dans le {@link #catalogue} du numéro, sinon
     * {@link Reason#PROVIDER_NOT_AVAILABLE}. Sans choix (nul ou blanc), l'opérateur prédit s'applique.
     */
    public Resolved resolve(String rawMsisdn, PawapayOperationKind kind, String expectedCurrency,
                            String chosenProvider, String context) {
        if (chosenProvider == null || chosenProvider.isBlank()) {
            return resolve(rawMsisdn, kind, expectedCurrency, context);
        }
        String wanted = chosenProvider.trim().toUpperCase(Locale.ROOT);
        // Revue finale, point 3 (Minor 2) : un code mal formé (saisie client) ne doit jamais être
        // échoué avant validation. `provider` nul ici : UnsupportedNumberException#providerLabel
        // retombe sur PawapayProviders.label(null) = « Mobile money », donc aucun écho.
        if (!PawapayProviders.isWellFormed(wanted)) {
            throw new UnsupportedNumberException(Reason.PROVIDER_NOT_AVAILABLE, null, null);
        }
        Catalogue catalogue = catalogue(rawMsisdn, kind, expectedCurrency, context);
        PawapayProviderConfig conf = catalogue.option(wanted)
                .orElseThrow(() -> new UnsupportedNumberException(Reason.PROVIDER_NOT_AVAILABLE, wanted, null));
        return new Resolved(conf.provider(), catalogue.countryAlpha2(), catalogue.msisdn(), conf);
    }

    /**
     * Catalogue des opérateurs d'un numéro. Liste vide : l'exception porte la raison du prédit
     * (fermé, ou autre devise), comme {@code resolve} l'aurait fait.
     */
    public Catalogue catalogue(String rawMsisdn, PawapayOperationKind kind, String expectedCurrency, String context) {
        PawapayProviderPrediction prediction = predict(rawMsisdn, context);
        Map<String, PawapayProviderConfig> configuration = configuration(context);
        List<PawapayProviderConfig> options = new ArrayList<>();
        for (PawapayProviderConfig conf : configuration.values()) {
            boolean sameCountry = conf.countryAlpha3() != null
                    && conf.countryAlpha3().equalsIgnoreCase(prediction.countryAlpha3());
            boolean sameCurrency = conf.currency() != null && conf.currency().equalsIgnoreCase(expectedCurrency);
            if (sameCountry && sameCurrency && isOperational(conf, kind)) {
                options.add(conf);
            }
        }
        // Le prédit en tête : c'est lui que l'app pré-coche.
        options.sort(Comparator.comparing((PawapayProviderConfig c) -> !c.provider().equalsIgnoreCase(prediction.provider())));
        if (options.isEmpty()) {
            PawapayProviderConfig predicted = configuration.get(prediction.provider());
            if (!isOperational(predicted, kind)) {
                throw new UnsupportedNumberException(Reason.OPERATION_CLOSED, prediction.provider(), null);
            }
            throw new UnsupportedNumberException(Reason.CURRENCY_MISMATCH, prediction.provider(), predicted.currency());
        }
        String detected = options.stream().map(PawapayProviderConfig::provider)
                .filter(p -> p.equalsIgnoreCase(prediction.provider())).findFirst().orElse(null);
        String msisdn = normalizedMsisdn(prediction, rawMsisdn, context);
        String country = PawapayCountries.toAlpha2(prediction.countryAlpha3());
        if (country == null) {
            throw new UnsupportedNumberException(Reason.COUNTRY_UNKNOWN, prediction.provider(), null);
        }
        return new Catalogue(country, expectedCurrency.toUpperCase(Locale.ROOT), msisdn, detected, List.copyOf(options));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private PawapayProviderPrediction predict(String rawMsisdn, String context) {
        Optional<PawapayProviderPrediction> predicted;
        try {
            predicted = client.predictProvider(rawMsisdn);
        } catch (RestClientException e) {
            throw PawapayErrors.providerUnavailable("predict-provider", context, e);
        }
        return predicted.orElseThrow(() -> new UnsupportedNumberException(Reason.NO_PROVIDER, null, null));
    }

    private Map<String, PawapayProviderConfig> configuration(String context) {
        try {
            return client.activeConfiguration();
        } catch (RestClientException e) {
            throw PawapayErrors.providerUnavailable("active-configuration", context, e);
        }
    }

    private static boolean isOperational(PawapayProviderConfig conf, PawapayOperationKind kind) {
        return conf != null && (kind == PawapayOperationKind.PAYOUT ? conf.supportsPayout() : conf.supportsDeposit());
    }

    // Le numéro prédit vient de pawaPay, pas d'une saisie : hors bornes de Msisdn.normalize,
    // c'est pawaPay qui répond une donnée inexploitable, même famille que les appels réseau,
    // 502 et non 422.
    private static String normalizedMsisdn(PawapayProviderPrediction prediction, String rawMsisdn, String context) {
        try {
            return Msisdn.normalize(prediction.phoneNumber() != null ? prediction.phoneNumber() : rawMsisdn);
        } catch (IllegalArgumentException e) {
            throw PawapayErrors.providerUnavailable("msisdn-normalize", context, e);
        }
    }
}
