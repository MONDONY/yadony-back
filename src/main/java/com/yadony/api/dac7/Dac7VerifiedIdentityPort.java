package com.yadony.api.dac7;

import java.util.UUID;

/**
 * Identité vérifiée d'un voyageur (pièce d'identité contrôlée par le prestataire KYC), réalisée par le paquet
 * {@code kyc} ({@code KycDac7IdentityAdapter}) : même motif de port que {@code auth.TaxInfoRetentionPort}, sans
 * injection de service entre paquets. Lecture à la demande, jamais persistée ni journalisée.
 *
 * <p>Trois issues distinctes : une panne ou un délai dépassé du prestataire n'est JAMAIS confondu avec une identité
 * absente (un voyageur ne doit pas être écarté de la déclaration parce que le prestataire n'a pas répondu).
 */
public interface Dac7VerifiedIdentityPort {

    Lookup verifiedIdentity(UUID userId);

    enum Status {
        /** Pièce vérifiée relue. */
        FOUND,
        /** Aucune vérification aboutie, ou rien de lisible sur la pièce. */
        NONE,
        /** Prestataire injoignable pour l'instant (429, 5xx, délai, connexion) : à réessayer. */
        UNAVAILABLE,
        /** Erreur permanente (session effacée, accès refusé, réponse illisible) : revue humaine. */
        UNREADABLE
    }

    record Lookup(Status status, Dac7VerifiedIdentity identity) {

        public static Lookup found(Dac7VerifiedIdentity identity) {
            return new Lookup(Status.FOUND, identity);
        }

        public static Lookup none() {
            return new Lookup(Status.NONE, null);
        }

        public static Lookup unavailable() {
            return new Lookup(Status.UNAVAILABLE, null);
        }

        public static Lookup unreadable() {
            return new Lookup(Status.UNREADABLE, null);
        }
    }
}
