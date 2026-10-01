package com.yadony.api.matching;

import java.security.SecureRandom;

/**
 * Code de retrait à 6 chiffres, connu de l'expéditeur et du destinataire seulement.
 * Partagé par le scan de départ, la régénération par l'expéditeur et le changement
 * de destinataire. Son expiration suit {@link ArrivalRules#pickupCodeExpiry}.
 */
public final class PickupCodes {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PickupCodes() {}

    public static String newCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }
}
