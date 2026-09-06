package com.yadony.api.kyc.provider;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.kyc.VerifiedIdentitySnapshot;

import java.util.Optional;

/**
 * Un fournisseur de verification d'identite. Les quatre methodes couvrent exactement les
 * quatre endroits ou yadony a besoin de lui : ouvrir une session, l'abandonner, relire le nom
 * verifie pour preremplir Stripe Connect, et afficher l'etat au back-office.
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

    void abandonSession(String providerSessionId);

    Optional<VerifiedIdentitySnapshot> fetchVerifiedName(String providerSessionId);

    ProviderAdminView fetchAdminView(String providerSessionId);
}
