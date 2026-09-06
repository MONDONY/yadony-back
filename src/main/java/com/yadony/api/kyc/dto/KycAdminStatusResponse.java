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
 * @param stripeUnavailable {@code true} uniquement si l'appel au fournisseur a échoué — jamais
 *                          quand il n'y a simplement aucune session à interroger.
 * @param provider          fournisseur de la session courante ({@code STRIPE} ou {@code DIDIT}),
 *                          {@code null} si aucune ligne KYC n'existe.
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
        String provider
) {}
