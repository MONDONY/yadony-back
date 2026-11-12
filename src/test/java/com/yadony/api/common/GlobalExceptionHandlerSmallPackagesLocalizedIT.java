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

import java.time.LocalDate;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paquet 5 du défaut n° 9 : refus des petits packages (demandes de colis, messagerie, support, codes SMS et
 * e-mail, parrainage, vols, statut voyageur) rendus dans la langue de l'app (DispatcherServlet +
 * GlobalExceptionHandler + MessagesResolver). Les refus passent par leurs clés de production.
 */
class GlobalExceptionHandlerSmallPackagesLocalizedIT {

    @RestController
    static class ThrowingController {
        @GetMapping("/t/request-engaged")
        void requestEngaged() {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "package-request-engaged",
                    "problem.package-request-not-removable.engaged");
        }

        @GetMapping("/t/media-rate-limited")
        void mediaRateLimited() {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "media-rate-limited",
                    "problem.media-rate-limited.conversation");
        }

        @GetMapping("/t/support-field")
        void supportField() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "support-invalid-field",
                    "problem.support-invalid-field.too-long", Map.of("field", "subject"), "subject", 120);
        }

        @GetMapping("/t/email-otp-rate-limit")
        void emailOtpRateLimit() {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "rate-limit", "problem.rate-limit", 15);
        }

        @GetMapping("/t/sms-otp-invalid")
        void smsOtpInvalid() {
            throw YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, "phone-otp-invalid", "problem.otp-invalid");
        }

        @GetMapping("/t/referral-cooldown")
        void referralCooldown() {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "regeneration-cooldown",
                    "problem.regeneration-cooldown", 30);
        }

        @GetMapping("/t/flight-period")
        void flightPeriod() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "flights-period-too-long",
                    "problem.flights-period.too-long.departure", 31);
        }

        @GetMapping("/t/trip-limit-other")
        void tripLimitOther() {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "monthly-trip-limit-reached",
                    "problem.monthly-trip-limit-reached.other", 3,
                    new com.yadony.api.common.i18n.LocalizedDate(LocalDate.of(2026, 12, 1)));
        }

        @GetMapping("/t/trip-not-found")
        void tripNotFound() {
            throw YadonyNotFoundException.localized("problem.not-found.trip-id", "abc-123");
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
    @DisplayName("retrait d'une demande de colis engagée : FR et EN")
    void packageRequestNotRemovable() throws Exception {
        expect("/t/request-engaged", 409, "package-request-engaged",
                "Un envoi est engagé sur cette demande (paiement, commission ou colis). "
                        + "Traitez-le par un litige plutôt que par un retrait.",
                "A shipment is committed on this request (payment, commission or parcel). "
                        + "Handle it through a dispute rather than a removal.");
    }

    @Test
    @DisplayName("messagerie : limite quotidienne de photos, FR et EN")
    void messagingMediaRateLimited() throws Exception {
        expect("/t/media-rate-limited", 429, "media-rate-limited",
                "Limite quotidienne de photos atteinte pour cette conversation.",
                "Daily photo limit reached for this conversation.");
    }

    @Test
    @DisplayName("support : champ trop long, arguments injectés, propriété field conservée, titre FR/EN")
    void supportFieldTooLong() throws Exception {
        expect("/t/support-field", 422, "support-invalid-field",
                "Le champ subject depasse 120 caracteres",
                "The subject field exceeds 120 characters");
        mvc.perform(getJson("/t/support-field", "en"))
                .andExpect(jsonPath("$.field").value("subject"))
                .andExpect(jsonPath("$.title").value("Invalid Field"));
        mvc.perform(getJson("/t/support-field", null)).andExpect(jsonPath("$.title").value("Champ invalide"));
    }

    @Test
    @DisplayName("codes par e-mail et par SMS : délai injecté sans séparateur, code invalide partagé")
    void otpRefusals() throws Exception {
        expect("/t/email-otp-rate-limit", 429, "rate-limit",
                "Trop de codes demandés, réessaie dans 15 min",
                "Too many codes requested, try again in 15 min");
        expect("/t/sms-otp-invalid", 400, "phone-otp-invalid",
                "Code invalide ou expiré", "Invalid or expired code");
    }

    @Test
    @DisplayName("parrainage : délai de régénération, FR et EN")
    void referralCooldown() throws Exception {
        expect("/t/referral-cooldown", 429, "regeneration-cooldown",
                "Vous ne pouvez régénérer votre code qu'une fois tous les 30 jours",
                "You can only regenerate your code once every 30 days");
    }

    @Test
    @DisplayName("vols et statut voyageur : arguments numériques et date injectés")
    void flightsAndTravelerStatus() throws Exception {
        expect("/t/flight-period", 422, "flights-period-too-long",
                "La période de départ ne peut pas dépasser 31 jours.",
                "The departure period cannot exceed 31 days.");
        expect("/t/trip-limit-other", 403, "monthly-trip-limit-reached",
                "Vous avez atteint votre limite de 3 trajets ce mois-ci. Vous pourrez publier de nouveau à partir du 1er décembre 2026.",
                "You have reached your limit of 3 trips this month. You will be able to publish again from December 1, 2026.");
    }

    @Test
    @DisplayName("introuvable à valeur unique avec argument : FR et EN")
    void notFoundWithArgument() throws Exception {
        mvc.perform(getJson("/t/trip-not-found", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Trajet introuvable: abc-123"));
        mvc.perform(getJson("/t/trip-not-found", "en"))
                .andExpect(jsonPath("$.detail").value("Trip not found: abc-123"));
    }
}
