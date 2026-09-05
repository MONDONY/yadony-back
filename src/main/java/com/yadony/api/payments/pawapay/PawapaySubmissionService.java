package com.yadony.api.payments.pawapay;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.pawapay.dto.PawapayDepositRequest;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.dto.PawapayPayoutRequest;
import com.yadony.api.payments.pawapay.dto.PawapayRefundRequest;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

    private PawapayOperationEntity submit(PawapayOperationEntity op, Supplier<PawapayInitiationResult> call) {
        PawapayInitiationResult result;
        try {
            result = call.get();
        } catch (RestClientException e) {
            log.error("pawaPay {} {} : initiation sans réponse ({}) — laissée CREATED pour le poller",
                    op.getKind(), op.getId(), e.toString());
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "mobile-money-provider-unavailable",
                    "Mobile Money Provider Unavailable",
                    "Le service mobile money ne répond pas. Réessayez dans quelques instants.");
        }
        operations.markSubmitted(op.getId(), result);
        return operations.get(op.getId());
    }
}
