package com.yadony.api.payments.pawapay;

import com.yadony.api.payments.pawapay.dto.PawapayDepositRequest;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.dto.PawapayPayoutRequest;
import com.yadony.api.payments.pawapay.dto.PawapayRefundRequest;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * Soumission d'une opération : create (id en base) → HTTP → markSubmitted. Une erreur
 * réseau laisse l'opération en CREATED : le poller l'interroge par son id et la classe
 * SUBMIT_REJECTED si pawaPay ne la connaît pas. On ne renvoie JAMAIS la même opération
 * avec un autre id.
 */
@Service
public class PawapaySubmissionService {

    public static final String DEPOSIT_MESSAGE = "yadony envoi";
    public static final String PAYOUT_MESSAGE = "yadony versement";

    private static final Logger log = LoggerFactory.getLogger(PawapaySubmissionService.class);

    private final PawapayOperationService operations;
    private final PawapayClient client;

    public PawapaySubmissionService(PawapayOperationService operations, PawapayClient client) {
        this.operations = operations;
        this.client = client;
    }

    public PawapayOperationEntity submitDeposit(UUID paymentId, String msisdn, String provider, String country,
                                                BigDecimal amount, String currency, String clientReference,
                                                String successfulUrl, String failedUrl) {
        PawapayOperationEntity op = operations.create(PawapayOperationKind.DEPOSIT, paymentId, null,
                amount, currency, provider, country, msisdn);
        return submit(op, () -> client.initiateDeposit(new PawapayDepositRequest(op.getId(), op.getMsisdn(),
                provider, amount, currency, DEPOSIT_MESSAGE, clientReference, successfulUrl, failedUrl)));
    }

    /**
     * Dépôt de recharge du wallet : pas de paiement de colis (paymentId et
     * relatedOperationId absents), purpose {@code WALLET_TOPUP} porté sur
     * l'opération à la place de {@code paymentId}.
     */
    public PawapayOperationEntity submitWalletDeposit(UUID userId, String msisdn, String provider, String country,
                                                       BigDecimal amount, String currency, String clientReference,
                                                       String successfulUrl, String failedUrl) {
        PawapayOperationEntity op = operations.create(PawapayOperationKind.DEPOSIT, PawapayOperationPurpose.WALLET_TOPUP,
                userId, null, null, amount, currency, provider, country, msisdn);
        return submit(op, () -> client.initiateDeposit(new PawapayDepositRequest(op.getId(), op.getMsisdn(),
                provider, amount, currency, DEPOSIT_MESSAGE, clientReference, successfulUrl, failedUrl)));
    }

    public PawapayOperationEntity submitPayout(UUID paymentId, String msisdn, String provider, String country,
                                               BigDecimal amount, String currency, String clientReference) {
        PawapayOperationEntity op = operations.create(PawapayOperationKind.PAYOUT, paymentId, null,
                amount, currency, provider, country, msisdn);
        return submit(op, () -> client.initiatePayout(new PawapayPayoutRequest(op.getId(), op.getMsisdn(),
                provider, amount, currency, PAYOUT_MESSAGE, clientReference)));
    }

    public PawapayOperationEntity submitRefund(UUID paymentId, PawapayOperationEntity deposit, BigDecimal amount) {
        PawapayOperationEntity op = operations.create(PawapayOperationKind.REFUND, paymentId, deposit.getId(),
                amount, deposit.getCurrency(), deposit.getProvider(), deposit.getCountry(), deposit.getMsisdn());
        return submit(op, () -> client.initiateRefund(new PawapayRefundRequest(op.getId(), deposit.getId(),
                amount, deposit.getCurrency())));
    }

    /**
     * Remboursement de wallet, premier temps : réserve l'opération REFUND (purpose
     * {@code WALLET_REFUND}, sans paiement de colis) liée au dépôt d'origine, SANS appel
     * réseau. L'appelant pose l'identifiant sur son item et le commite, puis appelle
     * {@link #initiate} : un rollback de l'appelant ne peut plus effacer la trace d'une
     * opération déjà partie chez pawaPay (sinon la reprise en créerait une seconde).
     */
    public PawapayOperationEntity createWalletRefund(UUID userId, PawapayOperationEntity deposit, BigDecimal amount) {
        return operations.create(PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND, userId, null,
                deposit.getId(), amount, deposit.getCurrency(), deposit.getProvider(), deposit.getCountry(),
                deposit.getMsisdn());
    }

    /** Versement de repli d'un remboursement de wallet, premier temps : voir {@link #createWalletRefund}. */
    public PawapayOperationEntity createWalletPayout(UUID userId, String msisdn, String provider, String country,
                                                     BigDecimal amount, String currency) {
        return operations.create(PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND, userId, null,
                null, amount, currency, provider, country, msisdn);
    }

    /**
     * Second temps : envoie à pawaPay une opération REFUND ou PAYOUT réservée par
     * {@link #createWalletRefund} / {@link #createWalletPayout}, puis marque la soumission.
     * Renvoie la réponse synchrone de pawaPay et non l'entité relue : l'appelant peut tenir
     * l'opération dans son contexte de persistance, où une relecture rendrait l'état d'avant
     * {@code markSubmitted} (UPDATE en masse d'une autre transaction). Sans réponse, lève
     * {@link PawapayErrors#providerUnavailable()} et laisse l'opération CREATED au poller.
     */
    public PawapayInitiationResult initiate(PawapayOperationEntity op, String clientReference) {
        return switch (op.getKind()) {
            case REFUND -> send(op, () -> client.initiateRefund(new PawapayRefundRequest(op.getId(),
                    op.getRelatedOperationId(), op.getAmount(), op.getCurrency())));
            case PAYOUT -> send(op, () -> client.initiatePayout(new PawapayPayoutRequest(op.getId(), op.getMsisdn(),
                    op.getProvider(), op.getAmount(), op.getCurrency(), PAYOUT_MESSAGE, clientReference)));
            case DEPOSIT -> throw new IllegalArgumentException("Un dépôt s'initie par submitDeposit : " + op.getId());
        };
    }

    private PawapayOperationEntity submit(PawapayOperationEntity op, Supplier<PawapayInitiationResult> call) {
        send(op, call);
        return operations.get(op.getId());
    }

    private PawapayInitiationResult send(PawapayOperationEntity op, Supplier<PawapayInitiationResult> call) {
        PawapayInitiationResult result;
        try {
            result = call.get();
        } catch (RestClientException e) {
            log.error("pawaPay {} {} : initiation sans réponse ({}) — laissée CREATED pour le poller",
                    op.getKind(), op.getId(), e.toString());
            throw PawapayErrors.providerUnavailable();
        }
        operations.markSubmitted(op.getId(), result);
        return result;
    }
}
