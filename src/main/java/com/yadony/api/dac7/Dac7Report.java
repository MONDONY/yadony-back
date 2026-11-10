package com.yadony.api.dac7;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Calcul DAC7 d'une année civile (lot 3a), base de l'export XML du lot 3b. Ne contient aucune donnée d'identité
 * (nom, NIF, adresse) : seulement des identifiants et des montants.
 *
 * @param travelers                   voyageurs DÉCLARABLES à identité complète ayant au moins une opération dans
 *                                    l'année, triés par id
 * @param identityReviewTravelers     voyageurs déclarables dont l'identité est incomplète (compte supprimé, donnée
 *                                    manquante) : à revoir à la main, jamais déclarés en silence
 * @param outOfYearRefunds            remboursements AVANT versement dont l'opération et le remboursement ne tombent pas
 *                                    la même année civile : non déduits, ils appellent une déclaration rectificative
 * @param refundsToReconcile          remboursements à l'expéditeur APRÈS versement au voyageur : jamais déduits (le
 *                                    versement n'est pas repris), chacun daté de sa date, à réconcilier à la main
 * @param pendingSplits               partages admin décidés dont le versement au voyageur n'est pas terminé : non
 *                                    comptés tant que le transfert n'est pas fait
 * @param unknownResidenceTravelers   voyageurs payés dont le pays de résidence est absent ou invalide (à obtenir)
 * @param nonDeclarableTravelers      voyageurs payés résidant hors Union et hors partenaires (justification)
 * @param invalidPartnerJurisdictions codes partenaires ignorés car invalides : l'export doit refuser de partir
 * @param anomalies                   lignes à vérifier à la main avant tout export
 */
public record Dac7Report(int year, List<TravelerReport> travelers, List<TravelerReport> identityReviewTravelers,
                         List<OutOfYearRefund> outOfYearRefunds, List<RefundToReconcile> refundsToReconcile,
                         List<PendingSplit> pendingSplits, List<UUID> unknownResidenceTravelers,
                         List<UUID> nonDeclarableTravelers, List<String> invalidPartnerJurisdictions,
                         List<Anomaly> anomalies) {

    /** Un voyageur déclarable : toujours quatre trimestres (1 à 4), vides compris. */
    public record TravelerReport(UUID travelerId, String residenceCountry, boolean taxInfoProvided,
                                 Dac7IdentityStatus identityStatus, List<QuarterReport> quarters) {
    }

    /**
     * Un trimestre civil (UTC).
     *
     * @param operations         nombre d'opérations (colis payés ; un supplément ou une modification n'en ajoute pas)
     * @param byCurrency         montants par devise d'origine, triés par code
     * @param netEur             contrepartie versée, nette de la commission et des remboursements déduits, en euros
     * @param commissionEur      commission de Yadony, déclarée séparément, en euros
     * @param refundsDeductedEur remboursements avant versement déduits de {@code netEur}, en euros
     * @param eurComplete        faux si au moins une ligne n'a pas de taux : les montants en euros sont alors partiels
     * @param commissionComplete faux si la commission d'au moins une ligne est introuvable (anomalie
     *                           {@code COMMISSION_UNKNOWN}) : {@code commissionEur} est alors partielle
     */
    public record QuarterReport(int quarter, int operations, List<CurrencyAmounts> byCurrency, BigDecimal netEur,
                                BigDecimal commissionEur, BigDecimal refundsDeductedEur, boolean eurComplete,
                                boolean commissionComplete) {
    }

    /** Montants d'un trimestre dans une devise d'origine ({@code net} est déjà diminué de {@code refundsDeducted}). */
    public record CurrencyAmounts(String currency, BigDecimal net, BigDecimal commission, BigDecimal refundsDeducted,
                                  int operations) {
    }

    /** Remboursement à traiter par déclaration rectificative ({@code amountEur} au taux du jour de l'opération). */
    public record OutOfYearRefund(UUID travelerId, Dac7Source source, UUID sourceId, String currency, BigDecimal amount,
                                  Instant operationPaidAt, Instant refundedAt, int operationYear, int refundYear,
                                  BigDecimal amountEur) {
    }

    /** Remboursement après versement ({@code amountEur} au taux du jour de l'opération, nul si inconnu). */
    public record RefundToReconcile(UUID travelerId, Dac7Source source, UUID sourceId, String currency,
                                    BigDecimal amount, Instant operationPaidAt, Instant refundedAt,
                                    boolean refundDateEstimated, BigDecimal amountEur) {
    }

    /** Partage admin décidé, versement au voyageur non terminé ({@code status} : CLAIMED ou SENDER_REFUNDED). */
    public record PendingSplit(UUID travelerId, UUID paymentId, String currency, BigDecimal travelerPayout,
                               BigDecimal commission, String status, Instant decidedAt) {
    }

    public record Anomaly(AnomalyType type, UUID travelerId, Dac7Source source, UUID sourceId, String currency,
                          LocalDate day) {
    }

    public enum AnomalyType {
        /** Aucun taux de référence connu pour cette devise ce jour-là : montant en euros manquant. */
        EXCHANGE_RATE_MISSING,
        /** Commission d'un colis en espèces introuvable : comptée zéro, trimestre marqué incomplet. */
        COMMISSION_UNKNOWN,
        /** Date de remboursement estimée (dernière mise à jour du paiement, faute de trace d'audit). */
        REFUND_DATE_ESTIMATED,
        /** Date de livraison absente : dernière mise à jour du colis retenue, qui peut encore bouger. */
        DELIVERY_DATE_ESTIMATED,
        /** Paiement versé sans voyageur retrouvé (colis ou fil de négociation absent). */
        TRAVELER_UNKNOWN
    }
}
