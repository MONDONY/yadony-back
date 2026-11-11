package com.yadony.api.common;

import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parcours HTTP complet (DispatcherServlet + GlobalExceptionHandler + MessagesResolver) d'erreurs
 * métier localisées du paquet 1 : même type, même code, titre et détail selon Accept-Language.
 */
class GlobalExceptionHandlerLocalizedBusinessIT {

    @RestController
    static class ThrowingController {
        @GetMapping("/t/draft-limit")
        void draftLimit() {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "draft-limit-reached",
                    "problem.draft-limit-reached.upgrade", 3);
        }

        @GetMapping("/t/monthly-limit")
        void monthlyLimit() {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "pro-limit-reached",
                    "problem.pro-limit-reached", 5);
        }

        @GetMapping("/t/negotiation-open")
        void negotiationOpen() {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "negotiation-still-open",
                    "problem.negotiation-still-open", Map.of("negotiationStatus", "PENDING"));
        }

        @GetMapping("/t/publish-forbidden")
        void publishForbidden() {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "forbidden",
                    "problem.forbidden.publier-cette-annonce");
        }

        @GetMapping("/t/cancel-locked")
        void cancelLocked() {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "cancel-locked", "problem.cancel-locked");
        }

        @GetMapping("/t/token-required")
        void tokenRequired() {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "problem.auth.token-required");
        }

        @GetMapping("/t/wait-not-elapsed")
        void waitNotElapsed() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY,
                    "delivery-noshow-wait-not-elapsed", "problem.delivery-noshow-wait-not-elapsed",
                    Map.of("minWaitMinutes", 30), 30);
        }

        @GetMapping("/t/rating-window")
        void ratingWindow() {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "rating-window-expired",
                    "problem.rating.window-expired");
        }

        @GetMapping("/t/kyc-verified")
        void kycVerified() {
            throw new LocalizedResponseStatusException(HttpStatus.CONFLICT, "problem.kyc.already-verified");
        }

        @GetMapping("/t/kyc-user")
        void kycUser() {
            throw YadonyNotFoundException.localized("problem.kyc.user-not-found");
        }

        @GetMapping("/t/legacy-not-found")
        void legacyNotFound() {
            throw new YadonyNotFoundException("Texte fixe");
        }

        @GetMapping("/t/legacy")
        void legacy() {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "legacy", "Titre fixe", "Texte fixe");
        }
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getJson(String url) {
        // Comme l'app mobile : Accept JSON, le handler répond alors en application/problem+json.
        return get(url).accept(MediaType.APPLICATION_JSON);
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler(TestMessages.resolver()))
                .build();
    }

    @Test
    @DisplayName("limite de brouillons : anglais avec Accept-Language: en")
    void draftLimit_english() throws Exception {
        mvc.perform(getJson("/t/draft-limit").header("Accept-Language", "en"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Limit of 3 draft(s) reached. Upgrade to PRO to create more."))
                .andExpect(jsonPath("$.title").value("Draft Limit Reached"))
                .andExpect(jsonPath("$.code").value("draft-limit-reached"));
    }

    @Test
    @DisplayName("limite de brouillons : français avec fr, et sans en-tête")
    void draftLimit_frenchWithOrWithoutHeader() throws Exception {
        for (String header : new String[]{"fr", null}) {
            var request = getJson("/t/draft-limit");
            if (header != null) {
                request.header("Accept-Language", header);
            }
            mvc.perform(request)
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail")
                            .value("Limite de 3 brouillon(s) atteinte. Passez en PRO pour en créer davantage."));
        }
    }

    @Test
    @DisplayName("limite mensuelle de publication : FR inchangé, EN idiomatique")
    void monthlyLimit_bothLanguages() throws Exception {
        mvc.perform(getJson("/t/monthly-limit").header("Accept-Language", "fr-FR,fr;q=0.9"))
                .andExpect(jsonPath("$.detail")
                        .value("Vous avez atteint votre limite de 5 annonces ce mois-ci. Passez en PRO pour continuer."))
                .andExpect(jsonPath("$.title").value("Monthly announcement limit reached"));
        mvc.perform(getJson("/t/monthly-limit").header("Accept-Language", "en-US,en;q=0.9"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail")
                        .value("You have reached your limit of 5 trips this month. Upgrade to PRO to continue."));
    }

    @Test
    @DisplayName("négociation : le détail suit la langue, la propriété négociation est conservée")
    void negotiationOpen_keepsPropertiesAndFollowsLanguage() throws Exception {
        mvc.perform(getJson("/t/negotiation-open").header("Accept-Language", "en"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Only a finished price discussion can be archived or deleted."))
                .andExpect(jsonPath("$.negotiationStatus").value("PENDING"));
        mvc.perform(getJson("/t/negotiation-open"))
                .andExpect(jsonPath("$.detail")
                        .value("Seule une discussion de prix terminée peut être archivée ou supprimée."));
    }

    @Test
    @DisplayName("publication refusée : FR et EN")
    void publishForbidden_bothLanguages() throws Exception {
        mvc.perform(getJson("/t/publish-forbidden").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.detail").value("You are not allowed to publish this trip"));
        mvc.perform(getJson("/t/publish-forbidden").header("Accept-Language", "fr"))
                .andExpect(jsonPath("$.detail").value("Vous n'êtes pas autorisé à publier cette annonce"));
    }

    @Test
    @DisplayName("exception historique à texte fixe : inchangée quelle que soit la langue")
    void legacyException_unchanged() throws Exception {
        mvc.perform(getJson("/t/legacy").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.detail").value("Texte fixe"))
                .andExpect(jsonPath("$.title").value("Titre fixe"));
    }

    @Test
    @DisplayName("annulation refusée (colis en transit) : FR par défaut, EN avec Accept-Language: en")
    void cancelLocked_bothLanguages() throws Exception {
        mvc.perform(getJson("/t/cancel-locked"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("cancel-locked"))
                .andExpect(jsonPath("$.detail")
                        .value("Le colis est en transit (ou le départ est dépassé) : annulation impossible."));
        mvc.perform(getJson("/t/cancel-locked").header("Accept-Language", "en"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("cancel-locked"))
                .andExpect(jsonPath("$.detail")
                        .value("The parcel is in transit (or its departure date has passed): it can no longer be cancelled."));
    }

    @Test
    @DisplayName("authentification requise : FR et EN, code et statut inchangés")
    void tokenRequired_bothLanguages() throws Exception {
        mvc.perform(getJson("/t/token-required").header("Accept-Language", "fr"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Un token Firebase valide est requis"));
        mvc.perform(getJson("/t/token-required").header("Accept-Language", "en"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.detail").value("A valid sign-in token is required"));
    }

    @Test
    @DisplayName("attente du destinataire : l'argument est inséré dans les deux langues, la propriété est conservée")
    void waitNotElapsed_argumentAndProperty() throws Exception {
        mvc.perform(getJson("/t/wait-not-elapsed"))
                .andExpect(jsonPath("$.detail").value(
                        "Attendez le destinataire au moins 30 minutes après votre arrivée avant de le signaler absent."))
                .andExpect(jsonPath("$.minWaitMinutes").value(30));
        mvc.perform(getJson("/t/wait-not-elapsed").header("Accept-Language", "en-US"))
                .andExpect(jsonPath("$.detail").value(
                        "Wait for the recipient for at least 30 minutes after your arrival before reporting them absent."))
                .andExpect(jsonPath("$.minWaitMinutes").value(30));
    }

    @Test
    @DisplayName("avis : fenêtre de notation expirée en FR et EN")
    void ratingWindowExpired_bothLanguages() throws Exception {
        mvc.perform(getJson("/t/rating-window"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("La fenêtre de notation est expirée"))
                .andExpect(jsonPath("$.title").value("Unprocessable"));
        mvc.perform(getJson("/t/rating-window").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.detail").value("The rating window has expired"))
                .andExpect(jsonPath("$.code").value("rating-window-expired"));
    }

    @Test
    @DisplayName("KYC déjà vérifié (ResponseStatusException localisée) : détail et titre suivent la langue, type inchangé")
    void kycAlreadyVerified_bothLanguages() throws Exception {
        mvc.perform(getJson("/t/kyc-verified"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("KYC déjà vérifié"))
                .andExpect(jsonPath("$.title").value("KYC déjà vérifié"))
                .andExpect(jsonPath("$.type").value("https://yadony.app/errors/generic"));
        mvc.perform(getJson("/t/kyc-verified").header("Accept-Language", "en"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Identity already verified"))
                .andExpect(jsonPath("$.type").value("https://yadony.app/errors/generic"));
    }

    @Test
    @DisplayName("KYC utilisateur introuvable (YadonyNotFoundException localisée) : FR et EN ; l'ancienne forme reste telle quelle")
    void kycUserNotFound_bothLanguages() throws Exception {
        mvc.perform(getJson("/t/kyc-user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Utilisateur introuvable"))
                .andExpect(jsonPath("$.title").value("Not Found"));
        mvc.perform(getJson("/t/kyc-user").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.detail").value("User not found"));
        mvc.perform(getJson("/t/legacy-not-found").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.detail").value("Texte fixe"));
    }
}
