package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

/**
 * Câblage réel : l'échec final pawaPay faute de fonds écrit UNE alerte admin pour le wallet,
 * même si plusieurs opérations échouent de suite (dédup tant que l'alerte n'est pas résolue).
 */
@SpringBootTest
@ActiveProfiles("test")
class PawapayOutOfFundsAlertIT {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired PawapayOperationRepository operations;
    @Autowired AdminAlertRepository alerts;

    private PawapayOperationEntity saved(PawapayOperationKind kind) {
        return operations.saveAndFlush(new PawapayOperationEntity(UUID.randomUUID(), kind,
                PawapayOperationPurpose.WALLET_REFUND, UUID.randomUUID(), null, null,
                new BigDecimal("490.00"), "XAF", "MTN_MOMO_CMR", "CM", "237670000000"));
    }

    @Test
    void echecsSuccessifsFauteDeFonds_uneSeuleAlerteAdmin() {
        PawapayOperationEntity refund = saved(PawapayOperationKind.REFUND);
        PawapayOperationEntity payout = saved(PawapayOperationKind.PAYOUT);

        publisher.publishEvent(new PawapayOperationFailedEvent(refund.getId(), refund.getKind(),
                PawapayOperationPurpose.WALLET_REFUND, null, UUID.randomUUID(), "PAWAPAY_WALLET_OUT_OF_FUNDS", "m"));
        publisher.publishEvent(new PawapayOperationFailedEvent(payout.getId(), payout.getKind(),
                PawapayOperationPurpose.WALLET_REFUND, null, UUID.randomUUID(), "PAWAPAY_WALLET_OUT_OF_FUNDS", "m"));

        assertThat(alerts.findByTypeAndResolved("PAWAPAY_OUT_OF_FUNDS_CM_XAF", false)).hasSize(1);
    }
}
