package com.yadony.api.auth.events;

import java.util.List;
import java.util.UUID;

/**
 * Finalisation RGPD d'un compte : les sessions de vérification d'identité détenues par Didit
 * doivent être effacées chez lui.
 *
 * <p>Publié dans la transaction de {@code AccountFinalizationService#finalize}, qui y met la
 * session Didit de la ligne KYC capturée AVANT de la vider : c'était la seule référence à cette
 * session, et la vider sans effacer chez Didit laissait les pièces d'identité chez lui sans plus
 * aucun moyen de les retrouver. Le package {@code kyc} y ajoute lui-même les sessions refusées
 * ou révoquées de l'utilisateur ({@code kyc_schema.kyc_refused_sessions}).
 *
 * @param diditSessionIds sessions déjà connues de l'émetteur, éventuellement vide
 */
public record IdentitySessionsErasureRequestedEvent(UUID userId, List<String> diditSessionIds) {

    public IdentitySessionsErasureRequestedEvent {
        diditSessionIds = diditSessionIds == null ? List.of() : List.copyOf(diditSessionIds);
    }
}
