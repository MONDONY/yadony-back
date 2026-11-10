package com.yadony.api.travelerstatus;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.travelerstatus.dto.PublishingEligibilityResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Garde de publication : refuse (403) la publication d'un trajet quand la limite mensuelle de l'utilisateur
 * est atteinte. Partagé par tous les chemins qui créent ou activent un trajet visible des expéditeurs
 * ({@code AnnouncementService}, le trajet dédié créé par {@code NegotiationService}, les récurrences).
 * Ne s'applique jamais à un brouillon : l'appelant ne le consulte pas. À appeler dans la transaction qui
 * enregistre le trajet : le garde verrouille la ligne de l'utilisateur quand une limite s'applique.
 */
@Component
public class PublishingLimitGate {

    public static final String CODE_STATUS_REQUIRED = "traveler-status-required";
    public static final String CODE_LIMIT_REACHED = "monthly-limit-reached";
    public static final String CODE_GP_PRO_PENDING = "gp-pro-pending";

    private final PublishingEligibilityService eligibility;

    public PublishingLimitGate(PublishingEligibilityService eligibility) {
        this.eligibility = eligibility;
    }

    /**
     * @throws YadonyBusinessException 403 {@code traveler-status-required}, {@code monthly-limit-reached} ou
     *         {@code gp-pro-pending} quand la publication est refusée
     */
    public void assertCanPublish(UserEntity user) {
        PublishingEligibilityResponse e = eligibility.evaluateForPublishing(user);
        switch (e.reason()) {
            case OK -> { }
            case STATUS_REQUIRED -> throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                    CODE_STATUS_REQUIRED, "Traveler Status Required",
                    "Vous avez atteint votre limite de trajets ce mois-ci. "
                            + "Indiquez si vous voyagez à titre personnel ou en tant que GP Pro.");
            case LIMIT_REACHED -> throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                    CODE_LIMIT_REACHED, "Monthly Trip Limit Reached",
                    "Vous avez atteint votre limite de " + e.limit() + " trajet" + (e.limit() > 1 ? "s" : "")
                            + " ce mois-ci. Vous pourrez publier de nouveau à partir du " + e.resetsOn() + ".");
            case GP_PRO_PENDING -> throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                    CODE_GP_PRO_PENDING, "GP Pro Pending",
                    "Votre demande GP Pro est en cours de validation. "
                            + "Vous pourrez publier de nouveau une fois qu'elle sera validée.");
        }
    }
}
