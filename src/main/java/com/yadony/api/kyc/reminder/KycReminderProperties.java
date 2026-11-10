package com.yadony.api.kyc.reminder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Relances de vérification d'identité (push + e-mail à J+1, J+3, J+7). Détecté par
 * {@code @ConfigurationPropertiesScan}.
 *
 * <p>{@code retryableRejectionCodes} : refus Didit où le voyageur peut simplement réessayer. Les deux
 * premiers codes sont ceux déjà vus en base ; les autres viennent de la documentation Didit
 * (https://docs.didit.me/core-technology/id-verification/warnings-id-verification.md et
 * https://docs.didit.me/core-technology/liveness/warnings-liveness.md) : qualité d'image, document
 * expiré / non accepté / illisible, selfie raté. Âge, fraude, manipulation, doublon et liste noire
 * en sont exclus volontairement. {@code maxAnchorAge} : on ne relance pas les parcours dont l'ancre est plus
 * vieille (stock ancré à la migration, interrupteur allumé bien plus tard). Liste modifiable par configuration ; vide, aucun refus n'est relancé.
 */
@ConfigurationProperties(prefix = "yadony.kyc.verification-reminders")
public record KycReminderProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("P1D") Duration firstDelay,
        @DefaultValue("P3D") Duration secondDelay,
        @DefaultValue("P7D") Duration thirdDelay,
        @DefaultValue("P10D") Duration maxAnchorAge,
        @DefaultValue("10") int windowStartHour,
        @DefaultValue("20") int windowEndHour,
        @DefaultValue({
                "DOCUMENT_UNREADABLE", "DOCUMENT_EXPIRED", "DOCUMENT_NOT_SUPPORTED_FOR_APPLICATION",
                "COULD_NOT_RECOGNIZE_DOCUMENT", "IMAGE_TOO_BLURRY", "IMAGE_TOO_DARK", "IMAGE_TOO_BRIGHT",
                "IMAGE_RESOLUTION_TOO_LOW", "IMAGE_QUALITY_TOO_LOW", "DOCUMENT_NOT_FULLY_VISIBLE",
                "DOCUMENT_OCCLUSION_DETECTED", "NO_FACE_DETECTED", "LOW_FACE_QUALITY",
                "LOW_FACE_LUMINANCE", "HIGH_FACE_LUMINANCE", "MULTIPLE_FACES_DETECTED"
        }) List<String> retryableRejectionCodes) {

    public KycReminderProperties {
        if (windowStartHour < 0 || windowEndHour > 24 || windowStartHour >= windowEndHour) {
            throw new IllegalArgumentException("yadony.kyc.verification-reminders : fenêtre horaire invalide ("
                    + windowStartHour + "h-" + windowEndHour + "h)");
        }
        if (firstDelay.isZero() || firstDelay.isNegative() || secondDelay.isZero() || secondDelay.isNegative()
                || thirdDelay.isZero() || thirdDelay.isNegative()) {
            throw new IllegalArgumentException("yadony.kyc.verification-reminders : les délais doivent être positifs");
        }
        if (firstDelay.compareTo(secondDelay) >= 0 || secondDelay.compareTo(thirdDelay) >= 0) {
            throw new IllegalArgumentException(
                    "yadony.kyc.verification-reminders : les délais doivent être strictement croissants");
        }
        // Sans cela, la 3e relance ne serait jamais dans la borne d'âge de l'ancre.
        if (maxAnchorAge.compareTo(thirdDelay) <= 0) {
            throw new IllegalArgumentException(
                    "yadony.kyc.verification-reminders : max-anchor-age doit dépasser third-delay");
        }
    }
}
