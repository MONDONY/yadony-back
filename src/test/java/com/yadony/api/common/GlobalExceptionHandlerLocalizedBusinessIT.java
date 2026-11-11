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
}
