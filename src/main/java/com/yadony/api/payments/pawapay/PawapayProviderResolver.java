package com.yadony.api.payments.pawapay;

import com.yadony.api.common.Msisdn;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
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
        COUNTRY_UNKNOWN
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
        Optional<PawapayProviderPrediction> predicted;
        try {
            predicted = client.predictProvider(rawMsisdn);
        } catch (RestClientException e) {
            throw PawapayErrors.providerUnavailable("predict-provider", context, e);
        }
        PawapayProviderPrediction prediction = predicted
                .orElseThrow(() -> new UnsupportedNumberException(Reason.NO_PROVIDER, null, null));
        Map<String, PawapayProviderConfig> configuration;
        try {
            configuration = client.activeConfiguration();
        } catch (RestClientException e) {
            throw PawapayErrors.providerUnavailable("active-configuration", context, e);
        }
        PawapayProviderConfig conf = configuration.get(prediction.provider());
        boolean operational = conf != null
                && (kind == PawapayOperationKind.PAYOUT ? conf.supportsPayout() : conf.supportsDeposit());
        if (!operational) {
            throw new UnsupportedNumberException(Reason.OPERATION_CLOSED, prediction.provider(), null);
        }
        if (!conf.currency().equalsIgnoreCase(expectedCurrency)) {
            throw new UnsupportedNumberException(Reason.CURRENCY_MISMATCH, prediction.provider(), conf.currency());
        }
        // Le numéro prédit vient de pawaPay, pas d'une saisie : hors bornes de Msisdn.normalize,
        // c'est pawaPay qui répond une donnée inexploitable — même famille que les deux appels
        // ci-dessus, 502 et non 422.
        String msisdn;
        try {
            msisdn = Msisdn.normalize(prediction.phoneNumber() != null ? prediction.phoneNumber() : rawMsisdn);
        } catch (IllegalArgumentException e) {
            throw PawapayErrors.providerUnavailable("msisdn-normalize", context, e);
        }
        String country = PawapayCountries.toAlpha2(prediction.countryAlpha3());
        if (country == null) {
            throw new UnsupportedNumberException(Reason.COUNTRY_UNKNOWN, prediction.provider(), null);
        }
        return new Resolved(prediction.provider(), country, msisdn, conf);
    }
}
