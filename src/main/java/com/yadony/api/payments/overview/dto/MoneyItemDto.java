package com.yadony.api.payments.overview.dto;

import com.yadony.api.payments.overview.MoneyChannel;
import com.yadony.api.payments.overview.MoneyRole;
import com.yadony.api.payments.overview.MoneyState;
import com.yadony.api.payments.overview.ReleaseCondition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Un montant de l'aperçu « Mon argent », rattaché à un colis.
 *
 * @param amount          voyageur : net estimé (montant payé − commission) ; expéditeur : montant
 *                        payé moins ce qui a déjà été remboursé. {@code null} pour un colis en espèces
 *                        (le prix se règle de la main à la main, hors Yadony).
 * @param releaseAt       date de libération automatique, seulement quand elle est réellement
 *                        calculable (fin de la garde « destinataire absent ») ; sinon {@code null}
 *                        et seule {@code releaseCondition} s'applique.
 * @param settledAt       date de libération (ou de remboursement) déjà effective, sinon {@code null}.
 * @param counterpartyName prénom + initiale du nom de l'autre partie, jamais plus.
 * @param bidStatus       statut brut du colis ({@code ACCEPTED}, {@code HANDED_OVER}, {@code IN_TRANSIT},
 *                        {@code ARRIVED}, {@code COMPLETED}…), pour placer l'étape de la frise côté client.
 * @param weightKg        poids déclaré du colis, si connu.
 * @param cashCommissionStatus colis en espèces seulement : statut de la commission Yadony
 *                        ({@code CHARGED}, {@code PENDING}, {@code REFUNDED}…), sinon {@code null}.
 * @param arrivalDate     date d'arrivée effective du trajet (jour du départ si le voyage arrive le
 *                        jour même) : échéance prévisible d'un séquestre versé à la confirmation de
 *                        livraison. Ajoutée en fin d'objet (FLUTTER-HV, suite), les apps antérieures
 *                        l'ignorent.
 */
public record MoneyItemDto(
        UUID bidId,
        String trackingNumber,
        UUID announcementId,
        MoneyRole role,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        BigDecimal amount,
        String currency,
        MoneyChannel channel,
        MoneyState state,
        ReleaseCondition releaseCondition,
        OffsetDateTime releaseAt,
        OffsetDateTime settledAt,
        String counterpartyName,
        String bidStatus,
        BigDecimal weightKg,
        String cashCommissionStatus,
        LocalDate arrivalDate) {
}
