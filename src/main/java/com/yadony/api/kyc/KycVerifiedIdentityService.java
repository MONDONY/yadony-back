package com.yadony.api.kyc;

import com.stripe.model.identity.VerificationSession;
import com.stripe.param.identity.VerificationSessionRetrieveParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Relit les {@code verified_outputs} de la session Stripe Identity d'un utilisateur, pour
 * preremplir son onboarding Stripe Connect avec l'identite deja verifiee plutot que de la
 * lui redemander champ par champ.
 *
 * <p>Rien n'est stocke : {@code kyc_verifications} ne garde que l'identifiant de session,
 * et Stripe reste la seule source des donnees verifiees. Elles ne sont relues qu'a l'instant
 * du provisioning, cote serveur, et jamais journalisees.
 *
 * <p>Best-effort assume : tout echec (session purgee, reseau, outputs absents) rend
 * {@code empty} et l'appelant cree le compte sans prefill — l'utilisateur ressaisit alors
 * dans le formulaire Stripe, comme avant cette classe. Un prefill ne vaut jamais un 502.
 */
@Service
public class KycVerifiedIdentityService {

    private static final Logger log = LoggerFactory.getLogger(KycVerifiedIdentityService.class);

    private final KycRepository kycRepository;

    public KycVerifiedIdentityService(KycRepository kycRepository) {
        this.kycRepository = kycRepository;
    }

    public Optional<VerifiedIdentitySnapshot> forUser(UUID userId) {
        try {
            Optional<KycVerificationEntity> verification = kycRepository.findByUserId(userId);
            if (verification.isEmpty()
                    || verification.get().getStripeVerificationSessionId() == null
                    || verification.get().getStatus() != KycVerificationStatus.VERIFIED) {
                return Optional.empty();
            }

            // verified_outputs n'est pas dans la reponse par defaut : il faut l'expand.
            //
            // NE PAS y ajouter "verified_outputs.dob". La date de naissance est un champ
            // sensible chez Stripe : expanser verified_outputs ne rend que les champs PII
            // accessibles a une cle secrete standard, et dob n'en fait pas partie. La
            // demander explicitement avec notre cle ferait echouer l'appel entier, et le
            // catch plus bas viderait alors AUSSI le prefill nom + adresse qui fonctionne
            // aujourd'hui. La lire exigerait une cle restreinte dediee, qui de toute facon
            // n'ouvre les champs sensibles que 48 h apres la verification — bien trop court
            // pour un provisioning Connect qui peut survenir des semaines plus tard.
            //
            // Consequence assumee : dob est toujours null ici, et c'est la date saisie a
            // l'etape « Vos informations » qui prereplit Stripe Connect (voir
            // StripeV2AccountProvisioner.buildIndividual). Les champs dob du snapshot
            // restent cables pour le jour ou une cle restreinte serait mise en place.
            VerificationSession session = VerificationSession.retrieve(
                    verification.get().getStripeVerificationSessionId(),
                    VerificationSessionRetrieveParams.builder()
                            .addExpand("verified_outputs")
                            .build(),
                    null);

            VerificationSession.VerifiedOutputs outputs = session.getVerifiedOutputs();
            if (outputs == null) {
                return Optional.empty();
            }

            var dob = outputs.getDob();
            var address = outputs.getAddress();
            return Optional.of(new VerifiedIdentitySnapshot(
                    outputs.getFirstName(),
                    outputs.getLastName(),
                    dob != null ? dob.getDay() : null,
                    dob != null ? dob.getMonth() : null,
                    dob != null ? dob.getYear() : null,
                    address != null ? address.getLine1() : null,
                    address != null ? address.getLine2() : null,
                    address != null ? address.getCity() : null,
                    address != null ? address.getPostalCode() : null,
                    address != null ? address.getCountry() : null));
        } catch (Exception e) {
            // Jamais de donnees dans le log : seulement l'utilisateur et la classe d'erreur.
            log.warn("verified_outputs indisponibles pour l'utilisateur {} ({}) — provisioning sans prefill",
                    userId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
