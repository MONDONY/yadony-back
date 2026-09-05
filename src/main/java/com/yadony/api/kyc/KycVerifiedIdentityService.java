package com.yadony.api.kyc;

import com.yadony.api.kyc.provider.IdentityProviderResolver;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Relit l'identite verifiee d'un utilisateur pour preremplir son onboarding Stripe Connect
 * plutot que de la lui redemander champ par champ.
 *
 * <p>Connect et la verification d'identite sont deux choses independantes : Connect n'a
 * jamais consomme Stripe Identity, seulement des champs nom/prenom. Le prefill fonctionne
 * donc a l'identique quel que soit le fournisseur d'identite.
 *
 * <p>Rien n'est stocke : {@code kyc_verifications} ne garde que l'identifiant de session, et
 * le fournisseur reste la seule source des donnees verifiees. Elles ne sont relues qu'a
 * l'instant du provisioning, cote serveur, et jamais journalisees.
 *
 * <p>Best-effort assume : tout echec (session purgee, reseau, implementation du fournisseur
 * retiree) rend {@code empty} et l'appelant cree le compte sans prefill — l'utilisateur
 * ressaisit alors dans le formulaire Connect. Un prefill ne vaut jamais un 502.
 */
@Service
public class KycVerifiedIdentityService {

    private final KycRepository kycRepository;
    private final IdentityProviderResolver providers;

    public KycVerifiedIdentityService(KycRepository kycRepository,
                                      IdentityProviderResolver providers) {
        this.kycRepository = kycRepository;
        this.providers = providers;
    }

    public Optional<VerifiedIdentitySnapshot> forUser(UUID userId) {
        Optional<KycVerificationEntity> verification = kycRepository.findByUserId(userId);
        if (verification.isEmpty()
                || verification.get().getVerificationSessionId() == null
                || verification.get().getStatus() != KycVerificationStatus.VERIFIED) {
            return Optional.empty();
        }

        // Le fournisseur de la LIGNE, jamais le fournisseur actif : une identite verifiee du
        // temps de Stripe se relit chez Stripe, meme apres la bascule vers Didit.
        return providers.forRecord(verification.get().getProvider())
                .flatMap(provider -> provider.fetchVerifiedName(
                        verification.get().getVerificationSessionId()));
    }
}
