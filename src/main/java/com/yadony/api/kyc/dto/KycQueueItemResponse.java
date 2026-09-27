package com.yadony.api.kyc.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une ligne de la file de revue KYC.
 *
 * @param userPhone    telephone masque, seuls les quatre derniers chiffres restent lisibles
 * @param kycStatus    {@code users.kyc_status}
 * @param recordStatus {@code kyc_verifications.status}
 * @param queueStatus  etat metier de la ligne dans la file : {@code IN_REVIEW},
 *                     {@code IN_PROGRESS}, {@code NOT_STARTED}, {@code REJECTED}, {@code VERIFIED}
 * @param submittedAt  passage en revue chez le fournisseur, a defaut derniere mise a jour de la ligne
 * @param waitingHours heures d'attente d'une demande en revue, {@code null} pour les autres etats
 */
public record KycQueueItemResponse(UUID userId,
                                   String userName,
                                   String userPhone,
                                   String provider,
                                   String kycStatus,
                                   String recordStatus,
                                   String queueStatus,
                                   String rejectionCode,
                                   String rejectionReason,
                                   String decisionKind,
                                   LocalDateTime decidedAt,
                                   String decidedByAdminEmail,
                                   LocalDateTime submittedAt,
                                   Long waitingHours) {}
