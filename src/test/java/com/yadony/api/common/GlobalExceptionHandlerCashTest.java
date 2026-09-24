package com.yadony.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.cash.exception.CommissionChargeFailedException;
import com.yadony.api.payments.cash.exception.CommissionMethodMissingException;
import com.yadony.api.payments.cash.exception.InvalidPaymentMethodForAnnouncementException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class GlobalExceptionHandlerCashTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(TestMessages.resolver());

    @Test
    void commissionMethodMissingReturns422WithProblemDetail() {
        ProblemDetail pd = handler.handleCommissionMethodMissing(new CommissionMethodMissingException());
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(pd.getType().toString()).isEqualTo("https://yadony.app/errors/commission-method-missing");
        assertThat(pd.getTitle()).isEqualTo("Méthode de commission requise");
    }

    @Test
    void invalidPaymentMethodReturns422WithProblemDetail() {
        ProblemDetail pd = handler.handleInvalidPaymentMethod(
                new InvalidPaymentMethodForAnnouncementException("CASH non autorisé"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(pd.getType().toString()).isEqualTo("https://yadony.app/errors/invalid-payment-method-for-announcement");
        assertThat(pd.getTitle()).isEqualTo("Mode de paiement non autorisé");
    }

    @Test
    void commissionChargeFailedReturns402WithProblemDetail() {
        ProblemDetail pd = handler.handleCommissionChargeFailed(
                new CommissionChargeFailedException("Carte refusée", new RuntimeException()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED.value());
        assertThat(pd.getType().toString()).isEqualTo("https://yadony.app/errors/commission-charge-failed");
        assertThat(pd.getTitle()).isEqualTo("Débit de la commission refusé");
    }
}
