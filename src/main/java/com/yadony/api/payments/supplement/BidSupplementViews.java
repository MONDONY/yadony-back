package com.yadony.api.payments.supplement;

import com.yadony.api.common.StorageService;
import com.yadony.api.matching.dto.BidSupplementResponse;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Conversion d'un supplément en {@link BidSupplementResponse}, règle de visibilité comprise. */
final class BidSupplementViews {

    /** Durée de l'URL présignée de la photo. */
    static final Duration PHOTO_URL_TTL = Duration.ofHours(1);

    private BidSupplementViews() {
    }

    /**
     * @param forSender vrai pour l'expéditeur du colis : ni net, ni commission, ni statut de
     *                  commission (il retrouverait le net du voyageur par soustraction).
     */
    static BidSupplementResponse of(BidSupplementEntity s, boolean forSender, StorageService storage,
                                    LocalDateTime now) {
        return new BidSupplementResponse(
                s.getId(),
                s.getStatus().name(),
                s.getRail().name(),
                s.getCurrency(),
                forSender ? null : s.getNetAmount(),
                forSender ? null : s.getCommissionAmount(),
                s.getGrossAmount(),
                s.getReasonCode().name(),
                s.getReasonText(),
                s.getGridItemLabel(),
                s.getPhotoPurgedAt() != null ? null : storage.generatePresignedUrl(s.getPhotoKey(), PHOTO_URL_TTL),
                utc(s.getExpiresAt()),
                utc(s.getPaidAt()),
                forSender || s.getCommissionStatus() == null ? null : s.getCommissionStatus().name(),
                blocksHandover(s, now));
    }

    /**
     * La demande bloque-t-elle la remise ? Un paiement en vol, toujours ; une demande qui attend
     * l'expéditeur (ou la 3DS du voyageur), seulement avant son échéance : échue, l'expéditeur ne
     * peut plus y répondre, elle ne doit plus retenir le colis en attendant le filet.
     */
    static boolean blocksHandover(BidSupplementEntity s, LocalDateTime now) {
        return switch (s.getStatus()) {
            case PAYMENT_PENDING -> true;
            case PROPOSED, COMMISSION_PENDING -> s.getExpiresAt().isAfter(now);
            default -> false;
        };
    }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
