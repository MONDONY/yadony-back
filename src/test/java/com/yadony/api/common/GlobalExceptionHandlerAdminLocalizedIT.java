package com.yadony.api.common;

import com.yadony.api.common.i18n.TestMessages;
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

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paquet 6 du défaut n° 9 : refus de l'administration, de l'assistant, de DAC7 et des automatisations rendus dans
 * la langue de la requête (DispatcherServlet + GlobalExceptionHandler + MessagesResolver). Les refus passent par
 * leurs clés de production.
 */
class GlobalExceptionHandlerAdminLocalizedIT {

    @RestController
    static class ThrowingController {
        @GetMapping("/t/admin-principal")
        void adminPrincipal() {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "admin-principal-required",
                    "problem.admin.admin-principal-required");
        }

        @GetMapping("/t/alert-not-found")
        void alertNotFound() {
            throw YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "alert-not-found",
                    "problem.admin.alert-not-found");
        }

        @GetMapping("/t/snooze-invalid")
        void snoozeInvalid() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "alert-snooze-invalid",
                    "problem.admin.alert-snooze-invalid", String.valueOf(72));
        }

        @GetMapping("/t/assistant-quota")
        void assistantQuota() {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "assistant-quota-exceeded",
                    "problem.assistant.assistant-quota-exceeded", Map.of("retryAfter", 3600));
        }

        @GetMapping("/t/assistant-user-required")
        void assistantUserRequired() {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "assistant-user-required",
                    "problem.assistant.assistant-user-required");
        }

        @GetMapping("/t/tax-id-length")
        void taxIdLength() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "tax-info-invalid",
                    "problem.dac7.tax-info-invalid.tax-id-length", Map.of("field", "taxIdentificationNumber"),
                    String.valueOf(5), String.valueOf(20));
        }

        @GetMapping("/t/dac7-year-not-closed")
        void dac7YearNotClosed() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-year-not-closed",
                    "problem.dac7.dac7-year-not-closed", String.valueOf(2026), String.valueOf(2027));
        }

        @GetMapping("/t/pro-required")
        void proRequired() {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "pro-required",
                    "problem.automation.pro-required-2");
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
    @DisplayName("administration : droit refusé (authentification administrateur), FR et EN")
    void adminPrincipalRequired() throws Exception {
        expect("/t/admin-principal", 403, "admin-principal-required",
                "Authentification administrateur requise", "Administrator authentication required");
    }

    @Test
    @DisplayName("administration : alerte introuvable, FR et EN")
    void adminAlertNotFound() throws Exception {
        expect("/t/alert-not-found", 404, "alert-not-found", "Alerte introuvable", "Alert not found");
    }

    @Test
    @DisplayName("administration : saisie invalide, nombre injecté sans séparateur, FR et EN")
    void adminSnoozeInvalid() throws Exception {
        expect("/t/snooze-invalid", 422, "alert-snooze-invalid",
                "Le report va de 1 à 72 heures", "The snooze must be between 1 and 72 hours");
    }

    @Test
    @DisplayName("assistant : quota atteint, propriété retryAfter conservée, FR et EN")
    void assistantQuotaExceeded() throws Exception {
        expect("/t/assistant-quota", 429, "assistant-quota-exceeded",
                "Vous avez posé beaucoup de questions aujourd'hui. Réessayez demain.",
                "You have asked a lot of questions today. Try again tomorrow.");
        mvc.perform(getJson("/t/assistant-quota", "en")).andExpect(jsonPath("$.retryAfter").value(3600));
    }

    @Test
    @DisplayName("assistant : réservé aux comptes utilisateur, FR et EN")
    void assistantUserRequired() throws Exception {
        expect("/t/assistant-user-required", 403, "assistant-user-required",
                "L'assistant est réservé aux comptes utilisateur.",
                "The assistant is reserved for user accounts.");
    }

    @Test
    @DisplayName("DAC7 : saisie fiscale invalide, champ conservé, titre et détail FR et EN")
    void dac7TaxIdLength() throws Exception {
        expect("/t/tax-id-length", 422, "tax-info-invalid",
                "Le numéro doit compter de 5 à 20 caractères.",
                "The number must be between 5 and 20 characters long.");
        mvc.perform(getJson("/t/tax-id-length", "en"))
                .andExpect(jsonPath("$.field").value("taxIdentificationNumber"))
                .andExpect(jsonPath("$.title").value("Invalid Tax Info"));
    }

    @Test
    @DisplayName("DAC7 : année non terminée, deux arguments (année sans séparateur de milliers), FR et EN")
    void dac7YearNotClosed() throws Exception {
        expect("/t/dac7-year-not-closed", 422, "dac7-year-not-closed",
                "L'année 2026 n'est pas terminée : les récapitulatifs ne peuvent être envoyés qu'à partir du 1er janvier 2027.",
                "Year 2026 is not over yet: statements can only be sent from January 1, 2027.");
    }

    @Test
    @DisplayName("automatisations : réservé aux voyageurs PRO, FR et EN")
    void automationProRequired() throws Exception {
        expect("/t/pro-required", 403, "pro-required",
                "Automatisations réservées aux voyageurs PRO.", "Automations are reserved for PRO travelers.");
    }
}
