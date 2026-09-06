package com.yadony.api.kyc.provider.didit;

import com.fasterxml.jackson.databind.JsonNode;
import com.yadony.api.auth.UserEntity;
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

    private final DiditClient client;
    private final String kycReturnUrl;

    public DiditIdentityProvider(
            DiditClient client,
            @Value("${yadony.kyc.return-url:https://yadony.com/kyc/complete}") String kycReturnUrl) {
        this.client = client;
        this.kycReturnUrl = kycReturnUrl;
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
        try {
            JsonNode response = client.createSession(user.getId(), kycReturnUrl);
            String sessionId = text(response, "session_id");
            String url = text(response, "url");
            if (sessionId == null || url == null) {
                throw new IllegalStateException("réponse Didit sans session_id ni url");
            }
            return new ProviderSession(url, sessionId);
        } catch (Exception e) {
            log.error("Failed to create Didit session for user {} ({}){}",
                    user.getId(), e.getClass().getSimpleName(), diagnostic(e));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Impossible de créer la session de vérification");
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
