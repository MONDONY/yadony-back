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
    static BidSupplementResponse of(BidSupplementEntity s, boolean forSender, StorageService storage) {
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
                storage.generatePresignedUrl(s.getPhotoKey(), PHOTO_URL_TTL),
                utc(s.getExpiresAt()),
                utc(s.getPaidAt()),
                forSender || s.getCommissionStatus() == null ? null : s.getCommissionStatus().name(),
                s.getStatus().isOpen());
    }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
