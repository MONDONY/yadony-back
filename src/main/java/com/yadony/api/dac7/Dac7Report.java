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
 * <p>{@code identityStatus} ne regarde que le profil ({@code users}). Le lot 3b ({@code Dac7DeclarationService})
 * résout l'identité sur toutes les sources (profil, puis identité vérifiée par le prestataire KYC) et c'est son
 * verdict qui décide de l'export : un voyageur listé ici en revue d'identité peut s'y révéler complet.
 *
 * @param travelers                   voyageurs DÉCLARABLES à identité complète ayant au moins une opération dans
 *                                    l'année, triés par id
 * @param identityReviewTravelers     voyageurs déclarables dont l'identité est incomplète (compte supprimé, donnée
 *                                    manquante) : à revoir à la main, jamais déclarés en silence
 * @param refundsToReconcile          remboursements à l'expéditeur datés de CETTE année et liés à une opération versée
 *                                    au voyageur, chacun une seule fois, dans le rapport de son année, avec son
 *                                    traitement ({@link RefundTreatment}) : déduit (même année, argent repris au
 *                                    voyageur), année de l'opération à rectifier (année suivante, rectificative hors
 *                                    périmètre) ou rien (le voyageur garde l'argent)
 * @param pendingSplits               partages admin décidés dont le versement au voyageur n'est pas terminé : non
 *                                    comptés tant que le transfert n'est pas fait
 * @param unknownResidenceTravelers   voyageurs payés dont le pays de résidence est absent ou invalide (à obtenir)
 * @param nonDeclarableTravelers      voyageurs payés résidant hors Union et hors partenaires (justification)
 * @param invalidPartnerJurisdictions codes partenaires ignorés car invalides : l'export doit refuser de partir
 * @param anomalies                   lignes à vérifier à la main avant tout export
 */
public record Dac7Report(int year, List<TravelerReport> travelers, List<TravelerReport> identityReviewTravelers,
                         List<RefundToReconcile> refundsToReconcile,
                         List<PendingSplit> pendingSplits, List<UUID> unknownResidenceTravelers,
                         List<UUID> nonDeclarableTravelers, List<String> invalidPartnerJurisdictions,
                         List<Anomaly> anomalies) {

    /**
     * Un voyageur déclarable : toujours quatre trimestres (1 à 4), vides compris.
     *
     * @param currency devise des montants déclarés ({@link QuarterReport#net}) : la devise des paiements, sans
     *                 conversion, quand le voyageur n'a reçu qu'une devise dans l'année (BOI-INT-AEA-30-40 § 280) ;
     *                 sinon l'euro (taux de référence du jour de chaque opération)
     */
    public record TravelerReport(UUID travelerId, String residenceCountry, boolean taxInfoProvided,
                                 Dac7IdentityStatus identityStatus, List<QuarterReport> quarters, String currency) {

        public TravelerReport(UUID travelerId, String residenceCountry, boolean taxInfoProvided,
                              Dac7IdentityStatus identityStatus, List<QuarterReport> quarters) {
            this(travelerId, residenceCountry, taxInfoProvided, identityStatus, quarters, "EUR");
        }
    }

    /**
     * Un trimestre civil (UTC).
     *
     * @param operations         nombre d'opérations (colis payés ; un supplément ou une modification n'en ajoute pas)
     * @param byCurrency         montants par devise d'origine, triés par code
     * @param netEur             contrepartie versée, nette de la commission de Yadony, en euros (aucun remboursement
     *                           n'est jamais déduit : ils sont listés à part, à réconcilier)
     * @param commissionEur      commission de Yadony, déclarée séparément, en euros
     * @param eurComplete        faux si au moins une ligne n'a pas de taux : les montants en euros sont alors partiels
     * @param commissionComplete faux si la commission d'au moins une ligne est introuvable (anomalie
     *                           {@code COMMISSION_UNKNOWN}) : {@code commissionEur} est alors partielle
     * @param net                MONTANT DÉCLARÉ du trimestre, dans {@link TravelerReport#currency}, net des
     *                           remboursements déduits (le voyageur reçoit 100 % du prix : aucun frais retenu)
     * @param declaredComplete   faux si une conversion en euros était nécessaire et que le taux manque
     */
    public record QuarterReport(int quarter, int operations, List<CurrencyAmounts> byCurrency, BigDecimal netEur,
                                BigDecimal commissionEur, boolean eurComplete,
                                boolean commissionComplete, BigDecimal net, boolean declaredComplete) {

        /** Compatibilité : montant déclaré = montant en euros. */
        public QuarterReport(int quarter, int operations, List<CurrencyAmounts> byCurrency, BigDecimal netEur,
                             BigDecimal commissionEur, boolean eurComplete, boolean commissionComplete) {
            this(quarter, operations, byCurrency, netEur, commissionEur, eurComplete, commissionComplete, netEur,
                    eurComplete);
        }
    }

    /** Montants d'un trimestre dans une devise d'origine. */
    public record CurrencyAmounts(String currency, BigDecimal net, BigDecimal commission, int operations) {
    }

    /** Ce que le rapport fait d'un remboursement (BOI-INT-AEA-30-40 § 260). */
    public enum RefundTreatment {
        /** Même année civile que l'opération et argent repris au voyageur : retiré du trimestre de l'opération. */
        DEDUCTED,
        /** Année suivante : l'année de l'opération est à rectifier (rectificative hors périmètre), rien ne bouge. */
        TO_RECTIFY,
        /** Yadony a remboursé l'expéditeur sans rien reprendre au voyageur : le voyageur a gardé l'argent. */
        KEPT_BY_TRAVELER
    }

    /**
     * Remboursement à réconcilier ({@code amountEur} au taux du jour de l'opération, nul si inconnu).
     *
     * @param beforeRelease  vrai si l'expéditeur a été remboursé alors que l'argent était encore en séquestre (le
     *                       versement a pourtant eu lieu ensuite, du montant entier)
     * @param treatment      déduit, année à rectifier ou rien
     * @param deductedAmount montant retiré du déclaré, dans la devise du voyageur ; zéro hors {@code DEDUCTED}
     */
    public record RefundToReconcile(UUID travelerId, Dac7Source source, UUID sourceId, String currency,
                                    BigDecimal amount, Instant operationPaidAt, int operationYear, Instant refundedAt,
                                    int refundYear, boolean beforeRelease, boolean refundDateEstimated,
                                    BigDecimal amountEur, RefundTreatment treatment, BigDecimal deductedAmount) {

        /** Compatibilité : le voyageur garde l'argent, rien n'est déduit. */
        public RefundToReconcile(UUID travelerId, Dac7Source source, UUID sourceId, String currency,
                                 BigDecimal amount, Instant operationPaidAt, int operationYear, Instant refundedAt,
                                 int refundYear, boolean beforeRelease, boolean refundDateEstimated,
                                 BigDecimal amountEur) {
            this(travelerId, source, sourceId, currency, amount, operationPaidAt, operationYear, refundedAt,
                    refundYear, beforeRelease, refundDateEstimated, amountEur, RefundTreatment.KEPT_BY_TRAVELER,
                    BigDecimal.ZERO);
        }
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
        /** Ni remise ni livraison connues : dernière mise à jour du colis retenue, qui peut encore bouger. */
        DELIVERY_DATE_ESTIMATED,
        /**
         * Colis en espèces sans remise confirmée dans l'app (scan de départ) : date de livraison retenue à la place.
         * Choix tracé, non bloquant.
         */
        CASH_DATE_FROM_DELIVERY,
        /**
         * Colis en espèces remis dans l'année (scan de remise) mais pas encore livré : il n'est compté qu'une fois
         * terminé, daté de sa remise. À attendre avant de générer le fichier de l'année, sinon il manquera.
         */
        CASH_NOT_COMPLETED_YET,
        /**
         * Remboursement à déduire dont la conversion en euros manque, ou dont l'opération n'est pas dans le rapport :
         * rien n'est déduit, à traiter à la main.
         */
        REFUND_DEDUCTION_UNAVAILABLE,
        /** Paiement versé sans voyageur retrouvé (colis ou fil de négociation absent). */
        TRAVELER_UNKNOWN,
        /**
         * Lot 3b : jour du versement non publié par la BCE (week-end, férié) : taux du dernier jour publié avant lui
         * (au plus 7 jours). Signalé, non bloquant.
         */
        RATE_DATE_ESTIMATED
    }
}
