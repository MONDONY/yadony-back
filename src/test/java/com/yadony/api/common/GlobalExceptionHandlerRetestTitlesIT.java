package com.yadony.api.common;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.travelerstatus.PublishingEligibilityReason;
import com.yadony.api.travelerstatus.PublishingEligibilityService;
import com.yadony.api.travelerstatus.PublishingLimitGate;
import com.yadony.api.travelerstatus.dto.PublishingEligibilityResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retest partie C : titres des refus de publication et du code invalide dans la langue de la requête, date de la
 * limite mensuelle écrite dans cette langue, détail du refus « déclencheur obligatoire » des campagnes vidéo.
 * Le type, le statut et le code ne changent pas ; sans en-tête, le français reste la langue par défaut.
 */
class GlobalExceptionHandlerRetestTitlesIT {

    private static PublishingLimitGate gate(PublishingEligibilityReason reason, Integer limit) {
        PublishingEligibilityService eligibility = Mockito.mock(PublishingEligibilityService.class);
        PublishingEligibilityResponse response = new PublishingEligibilityResponse(false, reason, limit, limit,
                LocalDate.of(2026, 12, 1), null);
        when(eligibility.evaluateForPublishing(any(UserEntity.class))).thenReturn(response);
        when(eligibility.evaluateForPublishing(any(UserEntity.class), anyInt())).thenReturn(response);
        return new PublishingLimitGate(eligibility);
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/t/limit-one")
        void limitOne() {
            gate(PublishingEligibilityReason.LIMIT_REACHED, 1).assertCanPublish(new UserEntity());
        }

        @GetMapping("/t/limit-other")
        void limitOther() {
            gate(PublishingEligibilityReason.LIMIT_REACHED, 3).assertCanPublish(new UserEntity());
        }

        @GetMapping("/t/limit-fifteenth")
        void limitMidMonth() {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "monthly-limit-reached",
                    "problem.monthly-trip-limit-reached.other", 2,
                    new com.yadony.api.common.i18n.LocalizedDate(LocalDate.of(2027, 1, 15)));
        }

        @GetMapping("/t/status-required")
        void statusRequired() {
            gate(PublishingEligibilityReason.STATUS_REQUIRED, 1).assertCanPublish(new UserEntity());
        }

        @GetMapping("/t/gp-pro-pending")
        void gpProPending() {
            gate(PublishingEligibilityReason.GP_PRO_PENDING, 1).assertCanPublish(new UserEntity());
        }

        @GetMapping("/t/draft-limit")
        void draftLimit() {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "draft-limit-reached",
                    "problem.draft-limit-reached", 10);
        }

        @GetMapping("/t/draft-limit-upgrade")
        void draftLimitUpgrade() {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "draft-limit-reached",
                    "problem.draft-limit-reached.upgrade", 3);
        }

        @GetMapping("/t/not-a-draft")
        void notADraft() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "not-a-draft",
                    "problem.not-a-draft");
        }

        @GetMapping("/t/otp-invalid")
        void otpInvalid() {
            throw YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, "otp-invalid", "problem.otp-invalid");
        }

        @GetMapping("/t/otp-invalid-short")
        void otpInvalidShort() {
            throw YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, "otp-invalid",
                    "problem.otp-invalid.short");
        }

        @GetMapping("/t/triggers-required")
        void triggersRequired() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY,
                    "video-modal-triggers-required", "problem.video-modal-triggers-required");
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

    /** Titre et détail en français avec fr ou sans en-tête, en anglais avec en ; code et statut identiques. */
    private void expect(String url, int httpStatus, String code, String titleFr, String detailFr, String titleEn,
                        String detailEn) throws Exception {
        for (String header : new String[]{null, "fr", "fr-FR,fr;q=0.9"}) {
            mvc.perform(getJson(url, header))
                    .andExpect(status().is(httpStatus))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.type").value("https://yadony.app/errors/" + code))
                    .andExpect(jsonPath("$.title").value(titleFr))
                    .andExpect(jsonPath("$.detail").value(detailFr));
        }
        mvc.perform(getJson(url, "en"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type").value("https://yadony.app/errors/" + code))
                .andExpect(jsonPath("$.title").value(titleEn))
                .andExpect(jsonPath("$.detail").value(detailEn));
    }

    @Test
    @DisplayName("limite mensuelle : titre localisé, date « 1er décembre 2026 » / « December 1, 2026 »")
    void monthlyLimit() throws Exception {
        expect("/t/limit-one", 403, "monthly-limit-reached",
                "Limite mensuelle de trajets atteinte",
                "Vous avez atteint votre limite de 1 trajet ce mois-ci. Vous pourrez publier de nouveau à partir du 1er décembre 2026.",
                "Monthly Trip Limit Reached",
                "You have reached your limit of 1 trip this month. You will be able to publish again from December 1, 2026.");
        expect("/t/limit-other", 403, "monthly-limit-reached",
                "Limite mensuelle de trajets atteinte",
                "Vous avez atteint votre limite de 3 trajets ce mois-ci. Vous pourrez publier de nouveau à partir du 1er décembre 2026.",
                "Monthly Trip Limit Reached",
                "You have reached your limit of 3 trips this month. You will be able to publish again from December 1, 2026.");
    }

    @Test
    @DisplayName("date de la limite : jour autre que le 1er, sans « er »")
    void monthlyLimitDateOtherDay() throws Exception {
        expect("/t/limit-fifteenth", 403, "monthly-limit-reached",
                "Limite mensuelle de trajets atteinte",
                "Vous avez atteint votre limite de 2 trajets ce mois-ci. Vous pourrez publier de nouveau à partir du 15 janvier 2027.",
                "Monthly Trip Limit Reached",
                "You have reached your limit of 2 trips this month. You will be able to publish again from January 15, 2027.");
    }

    @Test
    @DisplayName("statut de voyageur requis et demande GP Pro en cours : titres localisés")
    void statusRequiredAndPending() throws Exception {
        expect("/t/status-required", 403, "traveler-status-required",
                "Statut de voyageur requis",
                "Vous avez atteint votre limite de trajets ce mois-ci. Indiquez si vous voyagez à titre personnel ou en tant que GP Pro.",
                "Traveler Status Required",
                "You have reached your trip limit for this month. Say whether you travel as an individual or as a GP Pro.");
        expect("/t/gp-pro-pending", 403, "gp-pro-pending",
                "Demande GP Pro en cours",
                "Votre demande GP Pro est en cours de validation. Vous pourrez publier de nouveau une fois qu'elle sera validée.",
                "GP Pro Pending",
                "Your GP Pro application is being reviewed. You will be able to publish again once it is approved.");
    }

    @Test
    @DisplayName("limite de brouillons et publication refusée : titres localisés")
    void draftRefusals() throws Exception {
        expect("/t/draft-limit", 403, "draft-limit-reached", "Limite de brouillons atteinte",
                "Limite de 10 brouillon(s) atteinte.", "Draft Limit Reached", "Limit of 10 draft(s) reached.");
        expect("/t/draft-limit-upgrade", 403, "draft-limit-reached", "Limite de brouillons atteinte",
                "Limite de 3 brouillon(s) atteinte. Passez en PRO pour en créer davantage.",
                "Draft Limit Reached", "Limit of 3 draft(s) reached. Upgrade to PRO to create more.");
        expect("/t/not-a-draft", 422, "not-a-draft", "Brouillon requis", "Seul un brouillon peut être publié",
                "Not A Draft", "Only a draft can be published");
    }

    @Test
    @DisplayName("code invalide : titre localisé, code otp-invalid inchangé")
    void otpInvalid() throws Exception {
        expect("/t/otp-invalid", 400, "otp-invalid", "Code invalide", "Code invalide ou expiré",
                "Invalid OTP", "Invalid or expired code");
        expect("/t/otp-invalid-short", 400, "otp-invalid", "Code invalide", "Code invalide",
                "Invalid OTP", "Invalid code");
    }

    @Test
    @DisplayName("campagne vidéo sans déclencheur : détail localisé, FR inchangé")
    void videoModalTriggersRequired() throws Exception {
        expect("/t/triggers-required", 422, "video-modal-triggers-required", "Validation échouée",
                "Au moins un déclencheur est obligatoire (sauf si la campagne a des situations)",
                "Validation Error",
                "At least one trigger is required (unless the campaign has situations)");
    }
}
