package com.yadony.api.payments.wallet.dto;

import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.ValidationHint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Statut d'une recharge mobile money tel que l'app le lit en boucle. Les huit statuts
 * pawaPay sont ramenés aux trois seuls que l'écran distingue : {@code PENDING} (rien à
 * faire, on attend le PIN ou le callback), {@code CONFIRMED} (le portefeuille est crédité,
 * {@code walletBalance} est renseigné), {@code FAILED} (l'utilisateur peut recommencer).
 *
 * @param walletBalance solde après crédit, uniquement sur {@code CONFIRMED} ; nul sinon,
 *                      pour ne pas laisser croire qu'un solde inchangé est un solde crédité
 * @param failureReason message d'échec traduit dans la langue de la requête ; nul hors échec
 * @param expiresAt     fin du délai de validation (création + 15 min)
 * @param validationHint comment l'opérateur demande la validation au payeur
 * @param failureCode   code d'échec Yadony stable ({@code insufficient-balance}, {@code other}…) ;
 *                      nul hors échec
 */
public record WalletTopupStatusResponse(UUID topupId, String status, BigDecimal amount, String currency,
                                        String provider, String providerLabel, String msisdnMasked,
                                        String authorizationUrl, String failureReason, BigDecimal walletBalance,
                                        Instant expiresAt, ValidationHint validationHint,
                                        String failureCode) {

    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String FAILED = "FAILED";

    /**
     * {@code SUBMIT_REJECTED} (refus à l'initiation, statut à nous) et {@code FAILED} (refus
     * de l'opérateur) sont le même échec pour l'utilisateur. Tous les autres, {@code
     * IN_RECONCILIATION} compris, restent en attente : tant que pawaPay n'a pas tranché, la
     * recharge peut encore aboutir.
     */
    public static String statusOf(PawapayOperationStatus status) {
        return switch (status) {
            case COMPLETED -> CONFIRMED;
            case FAILED, SUBMIT_REJECTED -> FAILED;
            case CREATED, ACCEPTED, PROCESSING, ENQUEUED, IN_RECONCILIATION -> PENDING;
        };
    }
}
