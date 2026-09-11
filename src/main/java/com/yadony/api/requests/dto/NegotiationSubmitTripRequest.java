package com.yadony.api.requests.dto;

import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Corps de {@code POST /negotiations/{id}/submit-trip} : le voyageur lie un trajet
 * existant au fil. Seul {@code travelerAnnouncementId} compte.
 *
 * <p>{@code paymentMethod} et {@code useCardForCommission} sont un vestige du flux
 * où le voyageur figeait le moyen de paiement en liant son trajet : depuis, c'est
 * l'expéditeur qui choisit au checkout parmi le SET calculé côté serveur
 * ({@code NegotiationService.submitTrip} remet {@code paymentMethod} à null). Les
 * deux champs restent acceptés pour l'application mobile, qui les envoie encore,
 * mais ne sont plus obligatoires : le portail PRO n'envoie que l'identifiant du
 * trajet et recevait un 422 pour un champ que personne ne lisait.
 */
public record NegotiationSubmitTripRequest(
    @NotNull UUID travelerAnnouncementId,
    /** Ignoré (voir la javadoc de la classe). Nullable pour les clients qui ne l'envoient pas. */
    PaymentMethod paymentMethod,
    /** Ignoré (voir la javadoc de la classe). Absent du JSON → false. */
    boolean useCardForCommission
) {
    public NegotiationSubmitTripRequest(UUID travelerAnnouncementId, PaymentMethod paymentMethod) {
        this(travelerAnnouncementId, paymentMethod, false);
    }

    public NegotiationSubmitTripRequest(UUID travelerAnnouncementId) {
        this(travelerAnnouncementId, null, false);
    }
}
