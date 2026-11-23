package com.yadony.api.cancellation.fee;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Brut que retiendrait le remboursement d'un colis et montant rendu, vus du paquet {@code cancellation} pour l'aperçu
 * montré à l'expéditeur avant d'annuler ({@link CancellationFeePreviewService}).
 *
 * <p>Le paquet {@code cancellation} décide si un frais s'applique et à quel taux ({@link CancellationFeeEligibility}) ;
 * le paquet {@code payments}, seul à connaître le paiement, son plafond converti et ce qui le rend inapte à porter une
 * retenue, répond par le MÊME calcul que le règlement (inversion de dépendance, comme {@link SenderRefundInFlightPort}).
 * Lecture seule, aucun verrou, aucun appel au prestataire.
 */
@FunctionalInterface
public interface CancellationFeeQuotePort {

    /**
     * @param rate taux du barème décidé par {@code cancellation} (0 si aucun frais ne s'applique)
     * @return le paiement en séquestre du colis et le brut retenu à ce taux ; vide si aucun paiement en ligne de
     *         l'expéditeur n'est remboursable pour ce colis (espèces, pas encore payé, déjà remboursé ou versé)
     */
    Optional<Quote> quote(UUID bidId, BigDecimal rate);

    /**
     * @param currency     devise du paiement
     * @param retainable   le paiement peut porter une retenue (carte capturée ou dépôt mobile money abouti du même
     *                     montant, ni legacy ni déjà partiellement remboursé) ; faux : remboursé en entier quoi qu'il arrive
     * @param grossFee     brut retenu au taux donné, 0 si {@code retainable} est faux
     * @param refundAmount rendu à l'expéditeur : le montant payé (frais de service compris) moins {@code grossFee}
     * @param capped       le plafond du barème a limité {@code grossFee} (calcul du règlement) ; faux sans retenue
     */
    record Quote(String currency, boolean retainable, BigDecimal grossFee, BigDecimal refundAmount, boolean capped) {}
}
