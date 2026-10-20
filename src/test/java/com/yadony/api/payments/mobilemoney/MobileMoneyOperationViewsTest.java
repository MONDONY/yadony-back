package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPaymentStatusResponse.OperationView;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayValidationHints;
import com.yadony.api.payments.pawapay.ValidationHint;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Vue d'un dépôt de colis : motif d'échec traduit, code Yadony stable et consigne de validation. */
class MobileMoneyOperationViewsTest {

    private final MobileMoneyOperationViews views = new MobileMoneyOperationViews(TestMessages.resolver(),
            new PawapayValidationHints(Map.of("ORANGE_CIV", "SMS_LINK")));

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    private static PawapayOperationEntity op(String provider, PawapayOperationStatus status, String failureCode) {
        PawapayOperationEntity o = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                UUID.randomUUID(), null, new BigDecimal("16800"), "XOF", provider, "SN", "221771234567");
        o.setStatus(status);
        o.setFailureCode(failureCode);
        o.setFailureMessage("Message brut de l'opérateur");
        return o;
    }

    @Test
    void view_failed_translatesReason() {
        OperationView v = views.view(op("ORANGE_SEN", PawapayOperationStatus.FAILED, "INSUFFICIENT_BALANCE"));

        assertThat(v.failureReasonCode()).isEqualTo("insufficient-balance");
        assertThat(v.failureReason()).isEqualTo("Solde insuffisant sur votre compte mobile money.");
        assertThat(v.failureMessage()).isEqualTo(v.failureReason());
        assertThat(v.failureCode()).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void view_failed_englishRequest_translatesInEnglish() {
        TestMessages.requestWithAcceptLanguage("en");

        OperationView v = views.view(op("ORANGE_SEN", PawapayOperationStatus.FAILED, "INSUFFICIENT_BALANCE"));

        assertThat(v.failureReason()).isEqualTo("Insufficient balance on your mobile money account.");
        assertThat(v.failureMessage()).isEqualTo("Insufficient balance on your mobile money account.");
        assertThat(v.failureReasonCode()).isEqualTo("insufficient-balance");
    }

    @Test
    void view_submitRejected_isAFailureToo() {
        OperationView v = views.view(op("ORANGE_SEN", PawapayOperationStatus.SUBMIT_REJECTED, "PAYMENT_NOT_APPROVED"));

        assertThat(v.failureReasonCode()).isEqualTo("not-approved");
        assertThat(v.failureReason()).isEqualTo("Le paiement n'a pas été validé sur votre téléphone.");
    }

    @Test
    void view_failedUnknownCode_isOther() {
        OperationView v = views.view(op("ORANGE_SEN", PawapayOperationStatus.FAILED, "WHATEVER"));

        assertThat(v.failureReasonCode()).isEqualTo("other");
        assertThat(v.failureReason()).isEqualTo("Le paiement a été refusé par l'opérateur.");
        assertThat(v.failureMessage()).doesNotContain("Message brut");
    }

    @Test
    void view_failedWithoutCode_isOther() {
        OperationView v = views.view(op("ORANGE_SEN", PawapayOperationStatus.FAILED, null));

        assertThat(v.failureReasonCode()).isEqualTo("other");
        assertThat(v.failureCode()).isNull();
    }

    @Test
    void view_accepted_noFailureFields_andHint() {
        PawapayOperationEntity accepted = op("ORANGE_CIV", PawapayOperationStatus.ACCEPTED, null);
        accepted.setFailureMessage(null);

        OperationView v = views.view(accepted);

        assertThat(v.failureCode()).isNull();
        assertThat(v.failureMessage()).isNull();
        assertThat(v.failureReasonCode()).isNull();
        assertThat(v.failureReason()).isNull();
        assertThat(v.validationHint()).isEqualTo(ValidationHint.SMS_LINK);
        assertThat(v.status()).isEqualTo("ACCEPTED");
        assertThat(v.providerLabel()).isNotBlank();
    }

    @Test
    void view_unknownProvider_hintIsGeneric() {
        assertThat(views.view(op("WAVE_SEN", PawapayOperationStatus.PROCESSING, null)).validationHint())
                .isEqualTo(ValidationHint.GENERIC);
    }

    @Test
    void rejectedDetail_isTheTranslatedReason() {
        PawapayOperationEntity rejected = op("ORANGE_SEN", PawapayOperationStatus.SUBMIT_REJECTED, "PAYER_LIMIT_REACHED");

        assertThat(views.rejectedDetail(rejected)).isEqualTo("Votre opérateur a refusé : plafond de paiement atteint.");
    }

    @Test
    void rejectedDetail_unknownCode_isOther() {
        assertThat(views.rejectedDetail(op("ORANGE_SEN", PawapayOperationStatus.SUBMIT_REJECTED, "WHATEVER")))
                .isEqualTo("Le paiement a été refusé par l'opérateur.");
    }
}
