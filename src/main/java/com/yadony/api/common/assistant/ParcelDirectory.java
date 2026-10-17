package com.yadony.api.common.assistant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Colis que l'assistant peut évoquer avec un utilisateur : ceux dont il est l'expéditeur, le
 * voyageur ou le destinataire confirmé. Implémenté par {@code matching/} ; l'assistant n'en
 * connaît ni les tables ni les règles d'accès.
 */
public interface ParcelDirectory {

    /** Colis en cours puis récents (clos depuis peu), les plus récents d'abord, au plus {@code limit}. */
    List<ParcelRef> recentParcels(String firebaseUid, int limit);

    /** Le colis portant ce numéro, seulement s'il est accessible à l'utilisateur. */
    Optional<ParcelRef> byTrackingNumber(String firebaseUid, String trackingNumber);

    /** Le colis {@code bidId}, seulement s'il est accessible à l'utilisateur. */
    Optional<ParcelRef> byId(String firebaseUid, UUID bidId);
}
