package com.yadony.api.kyc.provider.stripe;

import com.stripe.model.identity.VerificationSession;
import com.stripe.param.identity.VerificationSessionCreateParams;
import com.stripe.param.identity.VerificationSessionRetrieveParams;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * Verification d'identite par Stripe Identity.
 *
 * <p>Fournisseur historique de yadony. Toute la logique propre a Stripe vit ici pour qu'un
 * jour son retrait soit la suppression de cette classe, pas une refonte.
 */
@Component
public class StripeIdentityProvider implements IdentityVerificationProvider {

    private static final Logger log = LoggerFactory.getLogger(StripeIdentityProvider.class);

    private final String kycReturnUrl;
    private final String kycVerificationFlowId;

    public StripeIdentityProvider(
            @Value("${yadony.kyc.return-url:https://yadony.com/kyc/complete}") String kycReturnUrl,
            @Value("${yadony.kyc.verification-flow-id:}") String kycVerificationFlowId) {
        this.kycReturnUrl = kycReturnUrl;
        this.kycVerificationFlowId = kycVerificationFlowId;
    }

    @Override
    public VerificationProviderKind kind() {
        return VerificationProviderKind.STRIPE;
    }

    /**
     * Reutilise la session en cours plutot que d'en creer une seconde — a deux conditions.
     * Elle doit rester utilisable ({@code requires_input} : ni verified, ni canceled, ni
     * processing), et avoir ete creee avec la configuration de flow courante. Sans ce second
     * test, un changement de {@code verification-flow-id} ne prend jamais effet pour les
     * comptes deja PENDING : leur session inachevee est elle aussi {@code requires_input},
     * donc resservie indefiniment avec l'ancienne configuration.
     *
     * <p>Stripe ne dedoublonne pas les sessions lui-meme, contrairement a Didit : ce controle
     * est le prix de son API, pas une regle metier de yadony.
     */
    @Override
    public ProviderSession createSession(UserEntity user, String existingSessionId) {
        if (existingSessionId != null) {
            try {
                VerificationSession existingSession = VerificationSession.retrieve(existingSessionId);
                if (!"requires_input".equals(existingSession.getStatus())) {
                    log.info("Existing KYC session {} no longer resumable (status={}), creating new one",
                            existingSessionId, existingSession.getStatus());
                } else if (!matchesConfiguredFlow(existingSession)) {
                    log.info("Existing KYC session {} was created with flow {} but {} is configured, creating new one",
                            existingSessionId, existingSession.getVerificationFlow(), kycVerificationFlowId);
                } else {
                    return new ProviderSession(existingSession.getUrl(), existingSessionId);
                }
            } catch (Exception e) {
                log.warn("Could not retrieve existing KYC session {}, creating new one", existingSessionId);
            }
        }

        try {
            VerificationSessionCreateParams.Builder paramsBuilder = VerificationSessionCreateParams.builder()
                    .setReturnUrl(kycReturnUrl)
                    .putMetadata("user_id", user.getId().toString());

            if (kycVerificationFlowId != null && !kycVerificationFlowId.isBlank()) {
                // Le flow (configure dans le Dashboard Stripe) pilote type + options : mutuellement
                // exclusif avec setType/setOptions d'apres la doc Stripe, donc on ne les fixe pas ici.
                paramsBuilder.setVerificationFlow(kycVerificationFlowId);
            } else {
                paramsBuilder.setType(VerificationSessionCreateParams.Type.DOCUMENT)
                        .setOptions(
                                VerificationSessionCreateParams.Options.builder()
                                        .setDocument(
                                                VerificationSessionCreateParams.Options.Document.builder()
                                                        .setRequireLiveCapture(true)
                                                        .setRequireMatchingSelfie(true)
                                                        .addAllowedType(VerificationSessionCreateParams.Options.Document.AllowedType.ID_CARD)
                                                        .addAllowedType(VerificationSessionCreateParams.Options.Document.AllowedType.PASSPORT)
                                                        .addAllowedType(VerificationSessionCreateParams.Options.Document.AllowedType.DRIVING_LICENSE)
                                                        .build()
                                        )
                                        .build()
                        );
            }

            VerificationSession session = VerificationSession.create(paramsBuilder.build());
            return new ProviderSession(session.getUrl(), session.getId());

        } catch (Exception e) {
            log.error("Failed to create Stripe Identity session for user {}", user.getId(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Impossible de créer la session de vérification");
        }
    }

    /**
     * Une session Stripe porte le flow avec lequel elle a ete creee, ou {@code null} si elle
     * provient de l'ancien chemin type/options. Elle n'est reutilisable que si ce flow
     * correspond exactement a la configuration courante — flow retire compris, auquel cas
     * seules les sessions sans flow restent valables.
     */
    private boolean matchesConfiguredFlow(VerificationSession session) {
        String configured = (kycVerificationFlowId == null || kycVerificationFlowId.isBlank())
                ? null
                : kycVerificationFlowId;
        return Objects.equals(configured, session.getVerificationFlow());
    }

    /**
     * Best-effort : une session Stripe injoignable ou deja terminee ne doit jamais bloquer la
     * remise a zero locale.
     */
    @Override
    public void abandonSession(String providerSessionId) {
        if (providerSessionId == null) return;
        try {
            VerificationSession.retrieve(providerSessionId).cancel();
        } catch (Exception e) {
            log.warn("Could not cancel Stripe KYC session {}: {}", providerSessionId, e.getMessage());
        }
    }

    /**
     * Relit les {@code verified_outputs} de la session pour preremplir l'onboarding Stripe
     * Connect avec l'identite deja verifiee, plutot que de la redemander champ par champ.
     *
     * <p>NE PAS ajouter {@code "verified_outputs.dob"} a l'expand avec CETTE cle. La date de
     * naissance est un champ sensible : elle n'est pas accessible a une cle secrete standard
     * (doc Stripe « Access verification results », tableau des permissions). La demander ici
     * ferait echouer l'appel entier, et le catch plus bas viderait alors AUSSI le prefill du
     * nom, qui lui fonctionne.
     *
     * <p>La lire est possible, mais exige une cle restreinte dediee : permission Identity
     * « Access recent sensitive verification results » pour les 48 dernieres heures, ou
     * « Access all sensitive verification results » + une allowlist d'IP pour un acces sans
     * limite de temps (obligatoire ici : un compte Connect peut se creer des semaines apres la
     * verification). Stripe decourage explicitement cet acces long terme. Non mis en place :
     * Connect demande la date de naissance dans son propre formulaire, et l'economie porterait
     * sur un seul champ, saisi une fois dans la vie du compte.
     */
    @Override
    public Optional<VerifiedIdentitySnapshot> fetchVerifiedName(String providerSessionId) {
        if (providerSessionId == null) return Optional.empty();
        try {
            // verified_outputs n'est pas dans la reponse par defaut : il faut l'expand.
            VerificationSession session = VerificationSession.retrieve(
                    providerSessionId,
                    VerificationSessionRetrieveParams.builder()
                            .addExpand("verified_outputs")
                            .build(),
                    null);

            VerificationSession.VerifiedOutputs outputs = session.getVerifiedOutputs();
            if (outputs == null) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedIdentitySnapshot(outputs.getFirstName(), outputs.getLastName()));
        } catch (Exception e) {
            // Jamais de donnees dans le log : seulement la session et la classe d'erreur.
            log.warn("verified_outputs indisponibles pour la session {} ({}) — provisioning sans prefill",
                    providerSessionId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public ProviderAdminView fetchAdminView(String providerSessionId) {
        if (providerSessionId == null) return ProviderAdminView.absent();
        try {
            VerificationSession session = VerificationSession.retrieve(providerSessionId);
            VerificationSession.LastError lastError = session.getLastError();
            LocalDateTime createdAt = session.getCreated() != null
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(session.getCreated()), ZoneOffset.UTC)
                    : null;
            return new ProviderAdminView(
                    session.getStatus(),
                    lastError != null ? lastError.getCode() : null,
                    lastError != null ? lastError.getReason() : null,
                    createdAt,
                    false);
        } catch (Exception e) {
            log.warn("Stripe Identity unavailable for session {}: {}", providerSessionId, e.getMessage());
            return ProviderAdminView.unreachable();
        }
    }
}
