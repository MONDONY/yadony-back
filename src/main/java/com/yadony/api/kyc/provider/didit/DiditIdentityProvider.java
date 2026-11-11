package com.yadony.api.kyc.provider.didit;

import com.fasterxml.jackson.databind.JsonNode;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.kyc.VerifiedIdentityDetails;
import com.yadony.api.kyc.VerifiedIdentitySnapshot;
import com.yadony.api.kyc.provider.IdentityVerificationProvider;
import com.yadony.api.kyc.provider.ProviderAdminView;
import com.yadony.api.kyc.provider.ProviderSession;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Verification d'identite par Didit.
 *
 * <p>Le {@code callback} envoye a Didit est la meme URL de retour que celle du chemin Stripe :
 * la webview de l'application detecte la fin du parcours exactement comme avant, et le
 * changement de fournisseur ne se voit pas cote mobile.
 */
@Component
public class DiditIdentityProvider implements IdentityVerificationProvider {

    private static final Logger log = LoggerFactory.getLogger(DiditIdentityProvider.class);

    /** Statut Didit d'une session aboutie, tel que l'API l'ecrit — casse comprise. */
    private static final String APPROVED = "Approved";

    static final String CREDITS_ALERT = "KYC_DIDIT_CREDITS_EXHAUSTED";

    private final DiditClient client;
    private final String kycReturnUrl;
    private final AdminAlertEscalator alerts;

    public DiditIdentityProvider(
            DiditClient client,
            @Value("${yadony.kyc.return-url:https://yadony.com/kyc/complete}") String kycReturnUrl,
            AdminAlertEscalator alerts) {
        this.client = client;
        this.kycReturnUrl = kycReturnUrl;
        this.alerts = alerts;
    }

    @Override
    public VerificationProviderKind kind() {
        return VerificationProviderKind.DIDIT;
    }

    /**
     * {@code existingSessionId} est ignore : l'API Didit ne resert jamais une session
     * terminale et resert toujours une session inachevee du meme {@code vendor_data}. Le
     * controle manuel que reclame Stripe n'a pas d'equivalent necessaire ici.
     */
    @Override
    public ProviderSession createSession(UserEntity user, String existingSessionId) {
        return open(user, () -> client.createSession(
                user.getId(), kycReturnUrl, user.getPreferredLanguage().code()));
    }

    /**
     * Didit dedoublonne sur {@code vendor_data} : un suffixe unique force une session neuve quand
     * la session inachevee du meme utilisateur a ete refusee par un administrateur. Les webhooks
     * sont rapproches par {@code session_id}, le suffixe n'a aucun autre effet.
     */
    @Override
    public ProviderSession createFreshSession(UserEntity user) {
        String vendorData = user.getId() + ":" + java.util.UUID.randomUUID();
        return open(user, () -> client.createSession(
                user.getId(), kycReturnUrl, vendorData, user.getPreferredLanguage().code()));
    }

    private ProviderSession open(UserEntity user, java.util.function.Supplier<JsonNode> call) {
        ProviderSession session;
        try {
            JsonNode response = call.get();
            String sessionId = text(response, "session_id");
            String url = text(response, "url");
            if (sessionId == null || url == null) {
                throw new IllegalStateException("réponse Didit sans session_id ni url");
            }
            session = new ProviderSession(url, sessionId);
        } catch (Exception e) {
            log.error("Failed to create Didit session for user {} ({}){}",
                    user.getId(), e.getClass().getSimpleName(), diagnostic(e));
            if (isOutOfCredits(e)) {
                raiseCreditsAlert(user);
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Impossible de créer la session de vérification");
        }
        closeCreditsAlert();
        return session;
    }

    /**
     * Solde Didit épuisé : Didit répond 400 avec « You don't have enough credits… Please top up ».
     * Plus AUCUNE vérification d'identité ne démarre tant que personne ne recharge, et chaque
     * utilisateur ne voit qu'un « service indisponible » (YADONY-BACK-PROD-D, 03-04/11/2026 :
     * 13 refus sur trois comptes en deux jours). Le statut seul ne suffit pas à le reconnaître,
     * Didit renvoie aussi 400 pour une requête mal formée : c'est le texte qui décide.
     */
    private static boolean isOutOfCredits(Exception e) {
        if (!(e instanceof HttpClientErrorException erreur)) return false;
        String corps = erreur.getResponseBodyAsString();
        return corps != null && corps.toLowerCase(java.util.Locale.ROOT).contains("enough credits");
    }

    /**
     * Une seule alerte (Telegram) tant qu'elle n'est pas close, quel que soit le nombre de
     * refus. Ne doit jamais transformer le 503 attendu par l'app en 500 : un échec de l'alerte
     * est journalisé, rien de plus.
     */
    private void raiseCreditsAlert(UserEntity user) {
        try {
            alerts.raiseOnce(CREDITS_ALERT,
                    "Didit n'a plus de crédits : plus aucune vérification d'identité ne démarre. "
                            + "Recharger le solde sur https://business.didit.me (ou activer la recharge automatique).",
                    java.util.Map.of("provider", "DIDIT", "firstRefusedUserId", user.getId().toString()));
        } catch (RuntimeException alertFailure) {
            log.error("Alerte {} impossible à lever", CREDITS_ALERT, alertFailure);
        }
    }

    /**
     * Une session qui s'ouvre prouve que le solde est revenu : l'alerte se clôt seule, et la
     * prochaine panne en lèvera une nouvelle. Sans effet sur la session rendue si la base
     * refuse.
     */
    private void closeCreditsAlert() {
        try {
            alerts.resolveOpen(CREDITS_ALERT);
        } catch (RuntimeException alertFailure) {
            log.warn("Alerte {} impossible à clore", CREDITS_ALERT, alertFailure);
        }
    }

    /**
     * Sans effet : Didit n'expose aucun endpoint d'annulation. La session inachevee reste
     * ouverte et sera resservie au prochain demarrage — l'utilisateur reprend la ou il s'est
     * arrete, ce qui vaut mieux que de la detruire.
     */
    @Override
    public void abandonSession(String providerSessionId) {
        // Volontairement vide — voir le javadoc.
    }

    /**
     * Nom et prenom uniquement, et seulement si la session est aboutie. Ni date de naissance,
     * ni adresse, ni numero de piece, ni URL d'image : Stripe Connect redemande et revalide
     * ces champs dans son propre formulaire, et elargir ce perimetre ferait entrer des donnees
     * sensibles dans un chemin qui n'a pas besoin d'elles. Aucun contenu journalise.
     */
    @Override
    public Optional<VerifiedIdentitySnapshot> fetchVerifiedName(String providerSessionId) {
        if (providerSessionId == null) return Optional.empty();

        return client.retrieveDecision(providerSessionId)
                .filter(decision -> APPROVED.equalsIgnoreCase(text(decision, "status")))
                .map(decision -> decision.path("id_verifications"))
                .filter(JsonNode::isArray)
                .filter(verifications -> !verifications.isEmpty())
                .map(verifications -> verifications.get(0))
                .map(first -> new VerifiedIdentitySnapshot(
                        text(first, "first_name"), text(first, "last_name")))
                .filter(snapshot -> snapshot.givenName() != null || snapshot.surname() != null);
    }

    /**
     * DAC7 seulement (obligation légale de la plateforme) : nom, date de naissance et adresse de la pièce d'une session
     * aboutie, lus à la demande au moment de la déclaration, jamais journalisés ni persistés. L'adresse détaillée
     * ({@code parsed_address}) est préférée ; à défaut l'adresse en une ligne ; beaucoup de pièces (passeport, carte
     * d'identité récente) n'en portent aucune.
     */
    @Override
    public Optional<VerifiedIdentityDetails> fetchVerifiedIdentity(String providerSessionId) {
        if (providerSessionId == null) return Optional.empty();

        return client.retrieveDecision(providerSessionId)
                .filter(decision -> APPROVED.equalsIgnoreCase(text(decision, "status")))
                .map(decision -> decision.path("id_verifications"))
                .filter(JsonNode::isArray)
                .filter(verifications -> !verifications.isEmpty())
                .map(verifications -> verifications.get(0))
                .map(DiditIdentityProvider::details);
    }

    static VerifiedIdentityDetails details(JsonNode verification) {
        JsonNode parsed = verification.path("parsed_address");
        String free = firstNonBlank(text(verification, "formatted_address"), text(verification, "address"));
        String country = parsed.isObject() ? isoAlpha2(firstNonBlank(text(parsed, "country_code"),
                text(parsed, "country"))) : null;
        return new VerifiedIdentityDetails(
                blankToNull(text(verification, "first_name")),
                blankToNull(text(verification, "last_name")),
                birthDate(text(verification, "date_of_birth")),
                parsed.isObject() ? blankToNull(firstNonBlank(text(parsed, "street_1"), text(parsed, "address_line_1")))
                        : null,
                parsed.isObject() ? blankToNull(firstNonBlank(text(parsed, "street_2"), text(parsed, "address_line_2")))
                        : null,
                parsed.isObject() ? blankToNull(text(parsed, "postal_code")) : null,
                parsed.isObject() ? blankToNull(text(parsed, "city")) : null,
                country,
                blankToNull(free));
    }

    private static java.time.LocalDate birthDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(raw.strip().length() > 10 ? raw.strip().substring(0, 10) : raw.strip());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String isoAlpha2(String raw) {
        if (raw == null) return null;
        String code = raw.strip().toUpperCase(java.util.Locale.ROOT);
        return code.length() == 2 && java.util.Arrays.asList(java.util.Locale.getISOCountries()).contains(code)
                ? code : null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    @Override
    public ProviderAdminView fetchAdminView(String providerSessionId) {
        if (providerSessionId == null) return ProviderAdminView.absent();

        Optional<JsonNode> decision = client.retrieveDecision(providerSessionId);
        if (decision.isEmpty()) {
            return ProviderAdminView.unreachable();
        }

        JsonNode node = decision.get();
        JsonNode warnings = node.path("warnings");
        JsonNode firstWarning = warnings.isArray() && !warnings.isEmpty() ? warnings.get(0) : null;

        return new ProviderAdminView(
                text(node, "status"),
                firstWarning != null ? text(firstWarning, "risk") : null,
                firstWarning != null ? text(firstWarning, "short_description") : null,
                parseCreatedAt(node),
                false);
    }

    /** Didit horodate en ISO 8601 ; un format inattendu ne doit pas couter la vue entiere. */
    private LocalDateTime parseCreatedAt(JsonNode node) {
        String raw = text(node, "created_at");
        if (raw == null) return null;
        try {
            return OffsetDateTime.parse(raw).toLocalDateTime();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(raw);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /**
     * Detail exploitable quand Didit refuse la requete.
     *
     * <p>Un 4xx signale une requete mal formee de NOTRE cote : le corps liste alors les
     * champs fautifs ({@code {"workflow_id":["Must be a valid UUID."]}}), ce qui est la
     * seule information permettant de diagnostiquer sans rejouer l'appel a la main. Sans
     * elle, le journal ne disait que « BadRequest » — insuffisant pour agir.
     *
     * <p>Reserve aux 4xx et tronque : un 5xx n'apprend rien, et un corps de reponse n'a
     * jamais vocation a remplir les journaux. La requete n'envoyant aucune donnee
     * personnelle, le corps d'erreur n'en renvoie pas non plus.
     */
    private static String diagnostic(Exception e) {
        if (e instanceof HttpClientErrorException erreur) {
            String corps = erreur.getResponseBodyAsString();
            if (corps != null && !corps.isBlank()) {
                return " — " + erreur.getStatusCode() + " " + abrege(corps);
            }
            return " — " + erreur.getStatusCode();
        }
        return "";
    }

    private static String abrege(String texte) {
        return texte.length() <= 300 ? texte : texte.substring(0, 300) + "…";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
