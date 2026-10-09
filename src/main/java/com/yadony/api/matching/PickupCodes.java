package com.yadony.api.matching;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Code de retrait à 6 chiffres, connu de l'expéditeur et du destinataire seulement.
 * Partagé par le scan de départ, la régénération par l'expéditeur et le changement
 * de destinataire. Son expiration suit {@link ArrivalRules#pickupCodeExpiry}.
 */
public final class PickupCodes {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PickupCodes() {}

    /** Statuts où le colis est chez le voyageur et attend sa remise avec le code. */
    public static final Set<BidStatus> IN_TRAVELER_HANDS =
            Set.of(BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED);

    public static String newCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    /**
     * Le colis est chez le voyageur mais son code de retrait n'est plus utilisable :
     * effacé après trois essais faux, ou expiré (FLUTTER-G1, FLUTTER-G2). Seul
     * l'expéditeur peut en générer un nouveau ; le voyageur peut le lui demander.
     */
    public static boolean renewalNeeded(BidEntity bid, LocalDateTime nowUtc) {
        if (bid == null || !IN_TRAVELER_HANDS.contains(bid.getStatus())) {
            return false;
        }
        String code = bid.getConfirmationCode();
        if (code == null || code.isBlank()) {
            return true;
        }
        LocalDateTime expiry = bid.getConfirmationCodeExpiry();
        return expiry != null && nowUtc.isAfter(expiry);
    }
}
