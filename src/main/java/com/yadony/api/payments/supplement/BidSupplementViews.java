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
                                    BidSupplementWindow window) {
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
                blocksHandover(s, window.now(), window.commissionConfirmWindow()));
    }

    /**
     * La demande bloque-t-elle la remise ? Un paiement en vol, toujours ; une demande qui attend
     * l'expéditeur, seulement avant son échéance : échue, l'expéditeur ne peut plus y répondre, elle
     * ne doit plus retenir le colis en attendant le filet. Une commission espèces en attente de 3DS
     * ne bloque que pendant {@code commissionConfirmWindow} (30 min) : au-delà, le filet l'annule de
     * toute façon, le colis n'a pas à attendre son passage.
     */
    static boolean blocksHandover(BidSupplementEntity s, LocalDateTime now, Duration commissionConfirmWindow) {
        return switch (s.getStatus()) {
            case PAYMENT_PENDING -> true;
            case PROPOSED -> s.getExpiresAt().isAfter(now);
            case COMMISSION_PENDING -> s.getExpiresAt().isAfter(now)
                    && !commissionUnconfirmedTooLong(s, now, commissionConfirmWindow);
            default -> false;
        };
    }

    /**
     * Commission espèces jamais confirmée (3DS) dans le délai : même seuil que le filet
     * ({@code BidSupplementCashReconciliationJob}), qui annule la demande.
     */
    static boolean commissionUnconfirmedTooLong(BidSupplementEntity s, LocalDateTime now,
                                                Duration commissionConfirmWindow) {
        return s.getStatus() == BidSupplementStatus.COMMISSION_PENDING && s.getCreatedAt() != null
                && !s.getCreatedAt().plus(commissionConfirmWindow).isAfter(now);
    }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
