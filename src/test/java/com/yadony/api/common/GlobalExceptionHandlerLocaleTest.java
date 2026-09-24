package com.yadony.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.cash.exception.CommissionChargeFailedException;
import com.yadony.api.payments.cash.exception.CommissionMethodMissingException;
import com.yadony.api.payments.cash.exception.InvalidPaymentMethodForAnnouncementException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MultipartException;

/**
 * Les six textes propres à {@link GlobalExceptionHandler} suivent l'Accept-Language de la
 * requête (D5) : français sans requête (littéral d'origine, inchangé), anglais avec
 * {@code Accept-Language: en}. Le {@code type} et le {@code code} du ProblemDetail ne
 * changent jamais, quelle que soit la langue.
 */
class GlobalExceptionHandlerLocaleTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(TestMessages.resolver());

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    @Test
    @DisplayName("commission-method-missing : titre FR sans requête, EN avec Accept-Language")
    void commissionMethodMissing_followsAcceptLanguage() {
        ProblemDetail fr = handler.handleCommissionMethodMissing(new CommissionMethodMissingException());
        assertThat(fr.getTitle()).isEqualTo("Méthode de commission requise");

        TestMessages.requestWithAcceptLanguage("en");
        ProblemDetail en = handler.handleCommissionMethodMissing(new CommissionMethodMissingException());
        assertThat(en.getTitle()).isEqualTo("Service fee method required");

        assertThat(en.getType()).isEqualTo(fr.getType());
        assertThat(en.getProperties().get("code")).isEqualTo(fr.getProperties().get("code"));
    }

    @Test
    @DisplayName("invalid-payment-method-for-announcement : titre FR sans requête, EN avec Accept-Language")
    void invalidPaymentMethod_followsAcceptLanguage() {
        ProblemDetail fr = handler.handleInvalidPaymentMethod(
                new InvalidPaymentMethodForAnnouncementException("CASH non autorisé"));
        assertThat(fr.getTitle()).isEqualTo("Mode de paiement non autorisé");

        TestMessages.requestWithAcceptLanguage("en");
        ProblemDetail en = handler.handleInvalidPaymentMethod(
                new InvalidPaymentMethodForAnnouncementException("CASH non autorisé"));
        assertThat(en.getTitle()).isEqualTo("Payment method not allowed");

        assertThat(en.getType()).isEqualTo(fr.getType());
        assertThat(en.getProperties().get("code")).isEqualTo(fr.getProperties().get("code"));
    }

    @Test
    @DisplayName("commission-charge-failed : titre FR sans requête, EN avec Accept-Language")
    void commissionChargeFailed_followsAcceptLanguage() {
        ProblemDetail fr = handler.handleCommissionChargeFailed(
                new CommissionChargeFailedException("Carte refusée", new RuntimeException()));
        assertThat(fr.getTitle()).isEqualTo("Débit de la commission refusé");

        TestMessages.requestWithAcceptLanguage("en");
        ProblemDetail en = handler.handleCommissionChargeFailed(
                new CommissionChargeFailedException("Carte refusée", new RuntimeException()));
        assertThat(en.getTitle()).isEqualTo("Service fee charge declined");

        assertThat(en.getType()).isEqualTo(fr.getType());
        assertThat(en.getProperties().get("code")).isEqualTo(fr.getProperties().get("code"));
    }

    @Test
    @DisplayName("concurrent-update : détail FR sans requête, EN avec Accept-Language")
    void concurrentUpdate_followsAcceptLanguage() {
        var ex = new org.springframework.orm.ObjectOptimisticLockingFailureException(
                "NegotiationThreadEntity", UUID.randomUUID());

        ResponseEntity<ProblemDetail> fr = handler.handleOptimisticLock(ex);
        assertThat(fr.getBody().getDetail()).isEqualTo("La ressource a été modifiée simultanément, réessayez");

        TestMessages.requestWithAcceptLanguage("en");
        ResponseEntity<ProblemDetail> en = handler.handleOptimisticLock(ex);
        assertThat(en.getBody().getDetail()).isEqualTo("The resource was changed at the same time, please try again");

        assertThat(en.getBody().getType()).isEqualTo(fr.getBody().getType());
    }

    @Test
    @DisplayName("missing-input : détail FR sans requête, EN avec Accept-Language")
    void missingInput_followsAcceptLanguage() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("page", "int");

        ResponseEntity<ProblemDetail> fr = handler.handleMissingInput(ex);
        assertThat(fr.getBody().getDetail()).isEqualTo("Paramètre ou en-tête de requête requis manquant");

        TestMessages.requestWithAcceptLanguage("en");
        ResponseEntity<ProblemDetail> en = handler.handleMissingInput(ex);
        assertThat(en.getBody().getDetail()).isEqualTo("A required request parameter or header is missing");

        assertThat(en.getBody().getType()).isEqualTo(fr.getBody().getType());
    }

    @Test
    @DisplayName("bad-multipart : détail FR sans requête, EN avec Accept-Language")
    void badMultipart_followsAcceptLanguage() {
        MultipartException ex = new MultipartException("Current request is not a multipart request");

        ResponseEntity<ProblemDetail> fr = handler.handleMultipart(ex);
        assertThat(fr.getBody().getDetail()).isEqualTo("Requête multipart invalide ou fichier manquant");

        TestMessages.requestWithAcceptLanguage("en");
        ResponseEntity<ProblemDetail> en = handler.handleMultipart(ex);
        assertThat(en.getBody().getDetail()).isEqualTo("Invalid multipart request or missing file");

        assertThat(en.getBody().getType()).isEqualTo(fr.getBody().getType());
    }
}
