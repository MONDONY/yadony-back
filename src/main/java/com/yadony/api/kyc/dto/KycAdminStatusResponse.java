package com.yadony.api.kyc.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Vue KYC administrateur : les DEUX statuts locaux (public.users.kyc_status et
 * kyc_schema.kyc_verifications.status, maintenus en parallèle), enrichis par un appel
 * live à Stripe Identity sur la session courante.
 *
 * <p>Ni document ni URL présignée à exposer : les colonnes {@code id_document_encrypted}
 * et {@code selfie_url} ont été supprimées par {@code V46__kyc_cleanup.sql} — Stripe est la
 * seule source de vérité des pièces.
 *
 * <p>Pas d'historique non plus : {@code uq_kyc_user_id} impose une seule ligne par
 * utilisateur, chaque nouvelle session écrasant l'identifiant précédent. Cette vue décrit
 * donc la <em>session courante</em>.
 *
 * @param stripeUnavailable {@code true} uniquement si l'appel au fournisseur a échoué — jamais
 *                          quand il n'y a simplement aucune session à interroger.
 * @param provider          fournisseur de la session courante ({@code STRIPE} ou {@code DIDIT}),
 *                          {@code null} si aucune ligne KYC n'existe.
 * @param decisionKind      derniere decision manuelle ({@code APPROVED}, {@code REJECTED},
 *                          {@code REVOKED}) sur la session courante, {@code null} sinon.
 * @param decisionReason    motif interne de l'administrateur : visible du back-office seulement,
 *                          jamais renvoye a l'utilisateur.
 * @param providerSessionUrl lien vers la session dans la console du fournisseur, quand un modele
 *                          d'URL est configure pour lui ({@code yadony.kyc.console-url.*}).
 * @param history           20 dernieres entrees d'audit {@code kyc_verification} de la ligne,
 *                          de la plus recente a la plus ancienne.
 */
public record KycAdminStatusResponse(
        UUID userId,
        String kycStatus,
        String verificationStatus,
        String rejectionReason,
        String rejectionCode,
        // Les cinq champs préfixés `stripe` décrivent en réalité le fournisseur courant, quel
        // qu'il soit. Le nom est conservé parce que dony-admin, dépôt séparé, les consomme
        // tels quels ; le renommage se fera avec le retrait de Stripe Identity, en une fois.
        String stripeSessionId,
        String stripeStatus,
        String stripeLastErrorCode,
        String stripeLastErrorReason,
        LocalDateTime stripeCreatedAt,
        boolean stripeUnavailable,
        String provider,
        String decisionKind,
        LocalDateTime decidedAt,
        String decidedByAdminEmail,
        String decisionReason,
        String providerSessionUrl,
        List<KycHistoryEntry> history
) {

    /** Forme historique, sans decision ni historique : conservee pour le reset et ses appelants. */
    public KycAdminStatusResponse(UUID userId, String kycStatus, String verificationStatus,
                                  String rejectionReason, String rejectionCode, String stripeSessionId,
                                  String stripeStatus, String stripeLastErrorCode,
                                  String stripeLastErrorReason, LocalDateTime stripeCreatedAt,
                                  boolean stripeUnavailable, String provider) {
        this(userId, kycStatus, verificationStatus, rejectionReason, rejectionCode, stripeSessionId,
                stripeStatus, stripeLastErrorCode, stripeLastErrorReason, stripeCreatedAt,
                stripeUnavailable, provider, null, null, null, null, null, List.of());
    }

    /** Meme vue live, enrichie de la decision d'administration et de l'historique. */
    public KycAdminStatusResponse withReview(String decisionKind, LocalDateTime decidedAt,
                                             String decidedByAdminEmail, String decisionReason,
                                             String providerSessionUrl, List<KycHistoryEntry> history) {
        return new KycAdminStatusResponse(userId, kycStatus, verificationStatus, rejectionReason,
                rejectionCode, stripeSessionId, stripeStatus, stripeLastErrorCode,
                stripeLastErrorReason, stripeCreatedAt, stripeUnavailable, provider, decisionKind,
                decidedAt, decidedByAdminEmail, decisionReason, providerSessionUrl, history);
    }
}
