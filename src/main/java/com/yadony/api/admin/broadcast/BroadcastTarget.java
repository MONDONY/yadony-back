package com.yadony.api.admin.broadcast;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Ciblage valide et normalise d'un broadcast.
 *
 * <p>La validation vit dans le constructeur compact : aucun chemin ne peut fabriquer un
 * ciblage incoherent, ni depuis le contrôleur, ni depuis un test. Les champs hors sujet
 * sont remis a {@code null} pour que la ligne d'historique ne conserve pas une ville
 * saisie puis abandonnee.
 *
 * <p>⚠️ {@code CORRIDOR} exige les DEUX villes. Un demi-corridor est ambigu et les codes
 * pays, seule alternative, sont nullables en base — les villes sont les seules colonnes
 * fiables ({@code announcements.departure_city} / {@code arrival_city}, VARCHAR(100) NOT NULL).
 */
public record BroadcastTarget(BroadcastTargetType type, String origin, String destination, UUID userId) {

    public BroadcastTarget {
        if (type == null) {
            throw invalid("Le type de ciblage est obligatoire");
        }
        if (type == BroadcastTargetType.CORRIDOR) {
            if (isBlank(origin) || isBlank(destination)) {
                throw invalid("Un ciblage par corridor exige une ville de depart ET une ville d'arrivee");
            }
            origin = origin.trim();
            destination = destination.trim();
        } else {
            origin = null;
            destination = null;
        }
        if (type == BroadcastTargetType.USER) {
            if (userId == null) {
                throw invalid("Un ciblage par utilisateur exige un identifiant d'utilisateur");
            }
        } else {
            userId = null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static YadonyBusinessException invalid(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "broadcast-target-invalid", "Unprocessable Entity", detail);
    }
}
