package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PawapayOutOfFundsAlerterTest {

    @Mock PawapayOperationRepository operations;
    @Mock AdminAlertEscalator alerts;
    @InjectMocks PawapayOutOfFundsAlerter alerter;

    private PawapayOperationEntity walletRefundPayout() {
        return new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                PawapayOperationPurpose.WALLET_REFUND, UUID.randomUUID(), null, null,
                new BigDecimal("490.00"), "XOF", "ORANGE_CIV", "CI", "2250700000000");
    }

    private static PawapayOperationFailedEvent failed(PawapayOperationEntity op, String code) {
        return new PawapayOperationFailedEvent(op.getId(), op.getKind(), PawapayOperationPurpose.WALLET_REFUND,
                null, UUID.randomUUID(), code, "message");
    }

    @Test
    void manqueDeFonds_alerteUneFoisParWalletPaysEtDevise() {
        // Prod 18/10 : remboursement de solde 490 XOF Orange CI refusé, personne prévenu.
        PawapayOperationEntity op = walletRefundPayout();
        when(operations.findById(op.getId())).thenReturn(Optional.of(op));

        alerter.onFailed(failed(op, "PAWAPAY_WALLET_OUT_OF_FUNDS"));

        verify(alerts).raiseOnce(eq("PAWAPAY_OUT_OF_FUNDS_CI_XOF"), argThat((String d) -> d.contains("CI XOF")),
                argThat((Map<String, Object> m) -> "CI".equals(m.get("country")) && "XOF".equals(m.get("currency"))
                        && "PAYOUT".equals(m.get("kind")) && "490.00".equals(m.get("amount"))
                        && "ORANGE_CIV".equals(m.get("provider"))));
    }

    @Test
    void autreMotifDEchec_aucuneAlerteNiLecture() {
        PawapayOperationEntity op = walletRefundPayout();

        alerter.onFailed(failed(op, "PAYMENT_NOT_APPROVED"));

        verifyNoInteractions(operations, alerts);
    }

    @Test
    void operationIntrouvable_aucuneAlerte() {
        PawapayOperationEntity op = walletRefundPayout();
        when(operations.findById(op.getId())).thenReturn(Optional.empty());

        alerter.onFailed(failed(op, "PAWAPAY_WALLET_OUT_OF_FUNDS"));

        verifyNoInteractions(alerts);
    }

    @Test
    void alerteEnEchec_neCassePasLeTraitement() {
        PawapayOperationEntity op = walletRefundPayout();
        when(alerts.raiseOnce(anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("base"));

        assertThatCode(() -> alerter.alert(op, "PAWAPAY_WALLET_OUT_OF_FUNDS")).doesNotThrowAnyException();
    }

    @Test
    void alerteDirecte_autreCode_sansEffet() {
        alerter.alert(walletRefundPayout(), null);

        verifyNoInteractions(alerts);
    }

    @Test
    void soldeTropBasAvantSoumission_memeAlerteParWallet_sansOperation() {
        alerter.alertBalanceTooLow("CI", "XOF", "ORANGE_CIV", PawapayOperationKind.REFUND, new BigDecimal("477"),
                new BigDecimal("487.50"), new BigDecimal("473"));

        verify(alerts).raiseOnce(eq("PAWAPAY_OUT_OF_FUNDS_CI_XOF"), argThat((String d) -> d.contains("non envoyé")),
                argThat((Map<String, Object> m) -> "477".equals(m.get("amount"))
                        && "487.50".equals(m.get("walletBalance")) && "473".equals(m.get("maxSafe"))
                        && "REFUND".equals(m.get("kind")) && "false".equals(m.get("submitted"))));
        verifyNoInteractions(operations);
    }

    @Test
    void soldeTropBas_alerteEnEchec_neCassePasLAppelant() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(alerts)
                .raiseOnce(any(), any(), any());
        alerter.alertBalanceTooLow("CI", "XOF", "ORANGE_CIV", PawapayOperationKind.PAYOUT, null, null, null);
        verify(alerts).raiseOnce(any(), any(), any());
    }
}
