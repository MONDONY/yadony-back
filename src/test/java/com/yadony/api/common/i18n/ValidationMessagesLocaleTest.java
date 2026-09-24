package com.yadony.api.common.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vérifie, de bout en bout via MockMvc, que les messages de validation des DTO
 * (tâche A5) suivent l'en-tête {@code Accept-Language} de la requête, avec un
 * repli sur le français sans en-tête : sur une route authentifiée
 * ({@code POST /announcements}) et sur une route publique
 * ({@code POST /auth/sms-otp/send}).
 *
 * <p>Documente aussi (méthode {@link #contrainteHibernateParDefaut_suitAcceptLanguageSansAucunCode()})
 * le comportement d'une contrainte Hibernate par défaut, sans {@code message}
 * propre : c'était un doute du plan, résolu ci-dessous.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ValidationMessagesLocaleTest {

    @Autowired
    MockMvc mvc;

    private static UsernamePasswordAuthenticationToken traveler() {
        return new UsernamePasswordAuthenticationToken(
                "uid-validation-locale-test", null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static UsernamePasswordAuthenticationToken sender() {
        return new UsernamePasswordAuthenticationToken(
                "uid-validation-locale-test-sender", null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void announcementValidation_sansEnTete_estEnFrancais() throws Exception {
        mvc.perform(post("/announcements")
                        .with(authentication(traveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.departureCity").value("La ville de départ est obligatoire"));
    }

    @Test
    void announcementValidation_avecAcceptLanguageEn_estEnAnglais() throws Exception {
        mvc.perform(post("/announcements")
                        .with(authentication(traveler()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.departureCity").value("The departure city is required"));
    }

    @Test
    void smsOtpSendValidation_routePublique_sansEnTete_estEnFrancais() throws Exception {
        mvc.perform(post("/auth/sms-otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"123\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.phoneNumber")
                        .value("Le numéro doit être au format E.164 (ex: +33612345678)"));
    }

    @Test
    void smsOtpSendValidation_routePublique_avecAcceptLanguageEn_estEnAnglais() throws Exception {
        mvc.perform(post("/auth/sms-otp/send")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"123\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.phoneNumber")
                        .value("The number must use the E.164 format (e.g. +33612345678)"));
    }

    /**
     * Correction 1 (tour de relecture) : un message d'offre (constat « Offres »),
     * repris à l'identique en français, désormais traduit en anglais lui aussi.
     * {@code BidRequest.photoKeys} et {@code BidNegotiationStartRequest.photoKeys}
     * partagent la même clé {@code validation.bid.photos.max}.
     */
    @Test
    void bidPhotosValidation_offre_suitAcceptLanguage() throws Exception {
        String tropDePhotos = "{\"photoKeys\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}";

        mvc.perform(post("/announcements/" + UUID.randomUUID() + "/bids")
                        .with(authentication(sender()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tropDePhotos))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.photoKeys").value("Maximum 4 photos"));

        mvc.perform(post("/announcements/" + UUID.randomUUID() + "/bids")
                        .with(authentication(sender()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tropDePhotos))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.photoKeys").value("Maximum 4 photos"));
    }

    /**
     * Correction 1 (tour de relecture) : messages de destinataire, en dur en
     * anglais avant cette correction (constat « Destinataires ») — un
     * francophone les voyait en anglais. Le français est désormais une
     * traduction voulue (« Le numéro doit être au format international
     * (E.164) »), l'anglais reprend le texte d'origine au caractère près.
     */
    @Test
    void recipientPhoneValidation_destinataire_suitAcceptLanguage() throws Exception {
        mvc.perform(post("/addressbook/recipients")
                        .with(authentication(sender()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneE164\":\"123\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.phoneE164")
                        .value("Le numéro doit être au format international (E.164)"));

        mvc.perform(post("/addressbook/recipients")
                        .with(authentication(sender()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneE164\":\"123\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.phoneE164").value("Phone must be in E.164 format"));
    }

    /**
     * Doute du plan : que devient un message de contrainte Hibernate par défaut
     * (sans {@code message} explicite) une fois le socle i18n en place ?
     *
     * <p>{@code SmsOtpVerifyRequest.phoneNumber} porte {@code @NotBlank} sans
     * message propre (hors table de la tâche A5, volontairement laissé tel
     * quel). Comportement observé et figé ici : Spring Boot enveloppe
     * l'interpolateur Hibernate par défaut dans un
     * {@code MessageSourceMessageInterpolator} construit sur NOTRE bean
     * {@code messageSource} (celui du socle, {@code I18nConfig}). Comme ce
     * bean ne connaît pas la clé {@code jakarta.validation.constraints.NotBlank.message},
     * il rend {@code null} et l'appel retombe sur l'interpolateur Hibernate
     * par défaut, appelé avec la MÊME {@code Locale} (celle posée par
     * {@code YadonyLocaleResolver} sur {@code LocaleContextHolder} via le
     * {@code DispatcherServlet}, jamais la locale JVM). Hibernate Validator
     * embarque lui-même des traductions ({@code ValidationMessages_fr.properties}
     * dans son propre jar) : la contrainte par défaut est donc DÉJÀ traduite
     * selon {@code Accept-Language}, sans aucun code applicatif — mais avec un
     * texte que ce dépôt ne maîtrise pas (bundle tiers, absent de
     * {@code messages_fr/en.properties}, hors du contrôle de
     * {@code MessagesBundleTest}).
     */
    @Test
    void contrainteHibernateParDefaut_suitAcceptLanguageSansAucunCode() throws Exception {
        mvc.perform(post("/auth/sms-otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.phoneNumber").value("ne doit pas être vide"));

        mvc.perform(post("/auth/sms-otp/verify")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.phoneNumber").value("must not be blank"));
    }
}
