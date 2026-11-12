package com.yadony.api.common;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.currency.PricePerKgBounds;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paquet 4b du défaut n° 9 : refus d'avenant, de remise (code de confirmation, QR) et de bornes de
 * prix rendus dans la langue de l'app (DispatcherServlet + GlobalExceptionHandler + MessagesResolver).
 * Les bornes de prix passent par le vrai code de production ; les autres refus par leurs clés.
 */
class GlobalExceptionHandlerAmendmentTrackingLocalizedIT {

    @RestController
    static class ThrowingController {
        @GetMapping("/t/amendment-expired")
        void amendmentExpired() {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "amendment-expired", "problem.amendment-expired");
        }

        @GetMapping("/t/amendment-not-pending")
        void amendmentNotPending() {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "amendment-not-pending",
                    "problem.amendment-not-pending");
        }

        @GetMapping("/t/code-incorrect")
        void codeIncorrect() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "code-incorrect",
                    "problem.code-incorrect.code-incorrect-tentative-s-restante-s", String.valueOf(2));
        }

        @GetMapping("/t/code-blocked")
        void codeBlocked() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "code-blocked", "problem.code-blocked");
        }

        @GetMapping("/t/qr-not-ready")
        void qrNotReady() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "qr-not-ready", "problem.qr-not-ready");
        }

        @GetMapping("/t/price-too-high")
        void priceTooHigh() {
            PricePerKgBounds.assertWithinBounds(new BigDecimal("999999999"), SupportedCurrency.EUR, "price-out-of-bounds");
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler(TestMessages.resolver()))
                .build();
    }

    private static MockHttpServletRequestBuilder getJson(String url, String acceptLanguage) {
        MockHttpServletRequestBuilder request = get(url).accept(MediaType.APPLICATION_JSON);
        return acceptLanguage == null ? request : request.header("Accept-Language", acceptLanguage);
    }

    private void expect(String url, int httpStatus, String code, String fr, String en) throws Exception {
        for (String header : new String[]{null, "fr", "fr-FR,fr;q=0.9"}) {
            mvc.perform(getJson(url, header))
                    .andExpect(status().is(httpStatus))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.detail").value(fr));
        }
        mvc.perform(getJson(url, "en"))
                .andExpect(status().is(httpStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").value(en));
    }

    @Test
    @DisplayName("avenant expiré : FR par défaut, EN avec Accept-Language, titre inchangé")
    void amendmentExpired() throws Exception {
        expect("/t/amendment-expired", 409, "amendment-expired",
                "Le délai de réponse à cette modification est dépassé",
                "The time to respond to this change has expired");
        mvc.perform(getJson("/t/amendment-expired", "en")).andExpect(jsonPath("$.title").value("Amendment Expired"));
    }

    @Test
    @DisplayName("avenant qui n'attend plus de réponse : FR et EN")
    void amendmentNotPending() throws Exception {
        expect("/t/amendment-not-pending", 409, "amendment-not-pending",
                "Cette modification n'attend plus de réponse",
                "This change is no longer awaiting a response");
    }

    @Test
    @DisplayName("code de retrait incorrect : nombre d'essais restants injecté, sans séparateur de milliers")
    void codeIncorrect() throws Exception {
        expect("/t/code-incorrect", 422, "code-incorrect",
                "Code incorrect — 2 tentative(s) restante(s)",
                "Incorrect code: 2 attempt(s) left");
    }

    @Test
    @DisplayName("code de retrait bloqué après trop d'essais : FR et EN")
    void codeBlocked() throws Exception {
        expect("/t/code-blocked", 422, "code-blocked",
                "Trop d'essais incorrects : ce code est bloqué. Demandez à l'expéditeur d'en générer un nouveau dans l'app",
                "Too many incorrect attempts: this code is blocked. Ask the sender to generate a new one in the app");
    }

    @Test
    @DisplayName("QR code pas encore disponible : FR et EN")
    void qrNotReady() throws Exception {
        expect("/t/qr-not-ready", 422, "qr-not-ready",
                "Le QR code n'est pas encore disponible pour cette transaction",
                "The QR code is not yet available for this transaction");
    }

    @Test
    @DisplayName("prix au kilo hors bornes (vrai code de production) : code, propriétés et langue")
    void priceOutOfBounds() throws Exception {
        expect("/t/price-too-high", 422, "price-out-of-bounds",
                "Ce prix au kilo dépasse le plafond autorisé dans cette devise.",
                "This price per kg exceeds the maximum allowed in this currency.");
        mvc.perform(getJson("/t/price-too-high", "en"))
                .andExpect(jsonPath("$.reason").value("too-high"))
                .andExpect(jsonPath("$.currency").value("EUR"));
    }
}
