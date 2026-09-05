package com.yadony.api.kyc.provider;

import com.yadony.api.config.PlatformSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Choisit l'implementation de verification d'identite.
 *
 * <p>La regle est asymetrique, et c'est tout l'interet : une session se <em>cree</em> chez le
 * fournisseur actif, mais se <em>relit</em> chez celui qui l'a produite. Une ligne verifiee du
 * temps de Stripe reste donc lisible apres la bascule vers Didit — le prefill de l'onboarding
 * Connect continue de fonctionner pour tous les comptes deja verifies.
 *
 * <p>{@link #forRecord} rend {@code empty} plutot que de lever quand l'implementation demandee
 * n'est plus deployee : le jour ou Stripe Identity est supprime, les vieilles lignes doivent
 * se degrader (pas de prefill, vue admin injoignable), jamais faire tomber un provisioning.
 */
@Component
public class IdentityProviderResolver {

    private final Map<VerificationProviderKind, IdentityVerificationProvider> providers =
            new EnumMap<>(VerificationProviderKind.class);
    private final PlatformSettingsService settings;

    public IdentityProviderResolver(List<IdentityVerificationProvider> providers,
                                    PlatformSettingsService settings) {
        providers.forEach(provider -> this.providers.put(provider.kind(), provider));
        this.settings = settings;
    }

    /** Fournisseur des nouvelles sessions, pilote par le reglage plateforme. */
    public IdentityVerificationProvider forCreation() {
        VerificationProviderKind kind = settings.kycDiditEnabled()
                ? VerificationProviderKind.DIDIT
                : VerificationProviderKind.STRIPE;
        IdentityVerificationProvider provider = providers.get(kind);
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Impossible de créer la session de vérification");
        }
        return provider;
    }

    /** Fournisseur d'une ligne existante, pilote par sa colonne — jamais par le reglage. */
    public Optional<IdentityVerificationProvider> forRecord(VerificationProviderKind kind) {
        return kind == null ? Optional.empty() : Optional.ofNullable(providers.get(kind));
    }
}
