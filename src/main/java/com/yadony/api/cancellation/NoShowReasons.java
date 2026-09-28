package com.yadony.api.cancellation;

import com.yadony.api.disputes.DisputeTypes;

import java.util.List;
import java.util.Set;

/**
 * Les motifs de {@code cancellations.reason} qui sont des déclarations de no-show,
 * et ce qui en découle pour l'arbitrage admin. Le voyageur absent au départ ne crée
 * pas de ligne (le bid passe directement en NO_SHOW), il n'apparaît donc pas ici.
 *
 * <ul>
 *   <li>{@code SENDER_NO_SHOW} (HANDOVER) : le voyageur déclare l'expéditeur absent ;</li>
 *   <li>{@code RECIPIENT_NO_SHOW} (DELIVERY) : le voyageur déclare le destinataire absent ;</li>
 *   <li>{@code TRAVELER_DELIVERY_NO_SHOW} (DELIVERY) : l'expéditeur déclare le voyageur absent à l'arrivée.</li>
 * </ul>
 */
public final class NoShowReasons {

    public static final String SENDER_NO_SHOW = CancellationReason.SENDER_NO_SHOW.name();

    public static final Set<String> ALL = Set.of(
            SENDER_NO_SHOW,
            DeliveryNoShowTypes.REASON_RECIPIENT_NO_SHOW,
            DeliveryNoShowTypes.REASON_TRAVELER_DELIVERY_NO_SHOW);

    private NoShowReasons() {
    }

    public static boolean isNoShow(String reason) {
        return reason != null && ALL.contains(reason);
    }

    /** Rôle de celui qui déclare : l'expéditeur pour un voyageur absent à l'arrivée, sinon le voyageur. */
    public static String declarantRole(String reason) {
        return DeliveryNoShowTypes.REASON_TRAVELER_DELIVERY_NO_SHOW.equals(reason) ? "SENDER" : "TRAVELER";
    }

    /** Rôle de celui qui est déclaré absent. */
    public static String accusedRole(String reason) {
        if (SENDER_NO_SHOW.equals(reason)) return "SENDER";
        if (DeliveryNoShowTypes.REASON_RECIPIENT_NO_SHOW.equals(reason)) return "RECIPIENT";
        return "TRAVELER";
    }

    /**
     * Types de litige qu'une déclaration peut avoir ouverts : contestation (les deux
     * portées) puis, pour la livraison, le litige « non contesté » ouvert à l'échéance.
     */
    public static List<String> linkedDisputeTypes(String reason) {
        if (SENDER_NO_SHOW.equals(reason)) return List.of(DisputeTypes.SENDER_NO_SHOW_CONTESTED);
        if (DeliveryNoShowTypes.REASON_RECIPIENT_NO_SHOW.equals(reason)
                || DeliveryNoShowTypes.REASON_TRAVELER_DELIVERY_NO_SHOW.equals(reason)) {
            return List.of(DeliveryNoShowTypes.contestedDisputeType(reason),
                    DeliveryNoShowTypes.uncontestedDisputeType(reason));
        }
        return List.of();
    }
}
