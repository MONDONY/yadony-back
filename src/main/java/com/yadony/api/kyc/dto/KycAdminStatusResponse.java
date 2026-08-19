package com.yadony.api.kyc.dto;

import java.time.LocalDateTime;
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
 * @param stripeUnavailable {@code true} uniquement si l'appel Stripe a échoué — jamais
 *                          quand il n'y a simplement aucune session à interroger.
 */
public record KycAdminStatusResponse(
        UUID userId,
        String kycStatus,
        String verificationStatus,
        String rejectionReason,
        String rejectionCode,
        String stripeSessionId,
        String stripeStatus,
        String stripeLastErrorCode,
        String stripeLastErrorReason,
        LocalDateTime stripeCreatedAt,
        boolean stripeUnavailable
) {}
