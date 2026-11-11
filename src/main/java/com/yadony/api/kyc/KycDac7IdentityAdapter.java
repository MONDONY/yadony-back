package com.yadony.api.kyc;

import com.yadony.api.dac7.Dac7Address;
import com.yadony.api.dac7.Dac7VerifiedIdentity;
import com.yadony.api.dac7.Dac7VerifiedIdentityPort;
import com.yadony.api.kyc.provider.IdentityProviderResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Réalise {@link Dac7VerifiedIdentityPort} : relit chez le prestataire qui a vérifié la pièce (fournisseur de la ligne,
 * jamais le fournisseur actif, comme {@link KycVerifiedIdentityService}) le nom, la date de naissance et l'adresse,
 * seulement pour une vérification aboutie. Rien n'est stocké : {@code kyc_verifications} ne garde que l'identifiant de
 * session. Rien n'est journalisé, sauf la classe d'une erreur.
 */
@Component
public class KycDac7IdentityAdapter implements Dac7VerifiedIdentityPort {

    private static final Logger log = LoggerFactory.getLogger(KycDac7IdentityAdapter.class);

    private final KycRepository kycRepository;
    private final IdentityProviderResolver providers;

    public KycDac7IdentityAdapter(KycRepository kycRepository, IdentityProviderResolver providers) {
        this.kycRepository = kycRepository;
        this.providers = providers;
    }

    @Override
    public Optional<Dac7VerifiedIdentity> verifiedIdentity(UUID userId) {
        try {
            Optional<KycVerificationEntity> verification = kycRepository.findByUserId(userId);
            if (verification.isEmpty() || verification.get().getVerificationSessionId() == null
                    || verification.get().getStatus() != KycVerificationStatus.VERIFIED) {
                return Optional.empty();
            }
            return providers.forRecord(verification.get().getProvider())
                    .flatMap(provider -> provider.fetchVerifiedIdentity(
                            verification.get().getVerificationSessionId()))
                    .map(KycDac7IdentityAdapter::toDac7);
        } catch (RuntimeException e) {
            log.warn("Identité vérifiée illisible pour DAC7 (utilisateur {}) : {}", userId,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static Dac7VerifiedIdentity toDac7(VerifiedIdentityDetails details) {
        Dac7Address address = details.city() == null && details.freeAddress() == null ? null
                : new Dac7Address(details.street(), details.line2(), details.postalCode(), details.city(),
                details.countryCode(), details.freeAddress());
        return new Dac7VerifiedIdentity(details.givenName(), details.surname(), details.birthDate(), address);
    }
}
