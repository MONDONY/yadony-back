package com.yadony.api.dac7;

import java.util.Optional;
import java.util.UUID;

/**
 * Identité vérifiée d'un voyageur (pièce d'identité contrôlée par le prestataire KYC), réalisée par le paquet
 * {@code kyc} ({@code KycDac7IdentityAdapter}) : même motif de port que {@code auth.TaxInfoRetentionPort}, sans
 * injection de service entre paquets. Lecture à la demande, jamais persistée ni journalisée ; vide si le voyageur n'a
 * pas de vérification aboutie ou si le prestataire ne répond pas.
 */
public interface Dac7VerifiedIdentityPort {

    Optional<Dac7VerifiedIdentity> verifiedIdentity(UUID userId);
}
