package com.yadony.api.kyc.provider;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.kyc.VerifiedIdentityDetails;
import com.yadony.api.kyc.VerifiedIdentitySnapshot;

import java.util.Optional;

/**
 * Un fournisseur de verification d'identite. Les quatre methodes couvrent exactement les
 * quatre endroits ou yadony a besoin de lui : ouvrir une session, l'abandonner, relire le nom
 * verifie pour preremplir Stripe Connect, et afficher l'etat au back-office. Une cinquieme,
 * facultative ({@link #fetchVerifiedIdentity}), relit l'identite complete pour la seule declaration
 * DAC7, obligation legale de la plateforme.
 *
 * <p>Aucune methode ne leve pour un probleme distant, sauf {@link #createSession} : l'abandon
 * et la vue admin sont best-effort, et un nom illisible rend {@code empty}. Un utilisateur qui
 * ne peut pas commencer, lui, doit le savoir.
 */
public interface IdentityVerificationProvider {

    VerificationProviderKind kind();

    /**
     * @param existingSessionId session deja enregistree pour cet utilisateur, ou {@code null}.
     *                          Stripe s'en sert pour reprendre une session encore utilisable ;
     *                          Didit l'ignore, son API decidant elle-meme de la reutilisation.
     */
    ProviderSession createSession(UserEntity user, String existingSessionId);

    /**
     * Ouvre une session NEUVE, jamais la reprise d'une session existante : appelee quand le
     * fournisseur a resservi une session qu'un administrateur a refusee. Par defaut, une creation
     * sans session existante suffit (Stripe cree alors toujours une nouvelle session).
     */
    default ProviderSession createFreshSession(UserEntity user) {
        return createSession(user, null);
    }

    void abandonSession(String providerSessionId);

    Optional<VerifiedIdentitySnapshot> fetchVerifiedName(String providerSessionId);

    /**
     * Nom, date de naissance et adresse lus sur la pièce vérifiée, pour la seule déclaration DAC7. Best-effort : vide
     * si la session n'est pas aboutie, illisible, ou si le fournisseur ne sait pas les relire (Stripe Identity, ancien
     * fournisseur, par défaut). Jamais journalisé ni persisté.
     *
     * @throws com.yadony.api.kyc.IdentityProviderUnavailableException si le fournisseur ne répond pas : une panne
     *         n'est jamais une absence de donnée
     */
    default Optional<VerifiedIdentityDetails> fetchVerifiedIdentity(String providerSessionId) {
        return Optional.empty();
    }

    ProviderAdminView fetchAdminView(String providerSessionId);
}
