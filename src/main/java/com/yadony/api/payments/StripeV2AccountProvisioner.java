package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.param.v2.core.AccountCreateParams;
import com.stripe.param.v2.core.AccountTokenCreateParams;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.kyc.KycVerifiedIdentityService;
import com.yadony.api.kyc.VerifiedIdentitySnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Création d'un compte connecté via l'API Accounts v2 ({@code POST /v2/core/accounts}),
 * en remplacement de l'API v1 que Stripe bloque désormais
 * ({@code v1_accounts_create_blocked}).
 *
 * <p>Le modèle de paiement est en <em>separate charges and transfers</em> — les fonds
 * restent sur le solde plateforme et le voyageur est réglé par {@code Transfer.create}
 * à la livraison, ce qui ne requiert que {@code stripe_balance.stripe_transfers}. Deux
 * formes de compte selon le pays :
 * <ul>
 *   <li><b>Recipient seul</b> (défaut, zone euro/SEPA + CH + GB) : onboarding réduit au
 *       strict nécessaire (identité + IBAN), sans vérification marchande. Ne jamais y
 *       demander {@code card_payments} — la greffe marchande après coup exige
 *       {@code mcc} + {@code phone} en {@code past_due} et désactive tout le compte
 *       (incident du 2026-09-02) ;</li>
 *   <li><b>Merchant + recipient</b> (US, CA — voir
 *       {@link StripeConnectCountries#requiresMerchantConfiguration}) : Stripe y refuse
 *       {@code stripe_transfers} sans {@code merchant.card_payments}. La création passe
 *       par un account token v2 ({@code account_token_required} pour une plateforme
 *       française) — voir {@link #provisionMerchantAndRecipient}.</li>
 * </ul>
 *
 * <p>Correspondances avec l'ancienne implémentation v1 : {@code type: express} devient
 * {@code dashboard: EXPRESS}, {@code business_type} devient {@code identity.entity_type},
 * {@code country} passe sous {@code identity}, et {@code business_profile.url} /
 * {@code product_description} passent sous {@code defaults.profile}. Le MCC disparaît
 * (aucun équivalent en v2, et non exigé pour un compte {@code recipient}), de même que le
 * planning de virement : {@code daily} est déjà le défaut d'un compte v2.
 */
@Component
public class StripeV2AccountProvisioner implements ConnectAccountProvisioner {

    private final StripeGateway stripeGateway;
    private final StripeConnectProperties stripeConnectProperties;
    private final FirebaseContactService firebaseContact;
    private final KycVerifiedIdentityService verifiedIdentity;

    public StripeV2AccountProvisioner(StripeGateway stripeGateway,
                                      StripeConnectProperties stripeConnectProperties,
                                      FirebaseContactService firebaseContact,
                                      KycVerifiedIdentityService verifiedIdentity) {
        this.stripeGateway = stripeGateway;
        this.stripeConnectProperties = stripeConnectProperties;
        this.firebaseContact = firebaseContact;
        this.verifiedIdentity = verifiedIdentity;
    }

    @Override
    public String provision(UserEntity user) throws StripeException {
        String country = user.getCountry();

        // Le pays d'un compte Connect est immuable apres creation : mieux vaut refuser
        // que fabriquer un compte sur un pays par defaut.
        if (country == null || country.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "country-required", "Country Required",
                    "Renseignez votre pays dans les reglages avant de creer votre "
                            + "compte de paiement.");
        }

        // yadony dessert des pays que Stripe ne couvre pas (zone XOF, zone XAF).
        // Sans cette garde, Stripe repond une erreur generique remontee en 500 : le
        // voyageur ne comprend pas qu'il doit simplement rester en especes.
        if (!StripeConnectCountries.isSupported(country)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "country-not-supported-by-stripe", "Country Not Supported",
                    "Le paiement par carte n'est pas encore disponible dans votre pays. "
                            + "Vous pouvez continuer a recevoir vos paiements en especes.");
        }

        // US / Canada : Stripe refuse stripe_transfers sans merchant.card_payments dans
        // ces pays. Le compte porte alors merchant + recipient, et la creation passe par
        // un account token (exigence account_token_required pour une plateforme FR).
        // Les 22 pays recipient-only gardent le chemin simple ci-dessous, inchange.
        if (StripeConnectCountries.requiresMerchantConfiguration(country)) {
            return provisionMerchantAndRecipient(user, country);
        }

        AccountCreateParams params = AccountCreateParams.builder()
                .setContactEmail(firebaseContact.getContact(user.getFirebaseUid()).email())
                // Reproduit l'experience d'onboarding hebergee de l'ancien type "express".
                .setDashboard(AccountCreateParams.Dashboard.EXPRESS)
                .setIdentity(buildIdentity(user, country))
                .setDefaults(
                        AccountCreateParams.Defaults.builder()
                                // Stripe impose APPLICATION des qu'un compte porte
                                // stripe_transfers : la valeur STRIPE est rejetee a la
                                // creation ("can only be application for the set of
                                // configurations this account has").
                                .setResponsibilities(
                                        AccountCreateParams.Defaults.Responsibilities.builder()
                                                .setLossesCollector(
                                                        AccountCreateParams.Defaults.Responsibilities
                                                                .LossesCollector.APPLICATION)
                                                .setFeesCollector(
                                                        AccountCreateParams.Defaults.Responsibilities
                                                                .FeesCollector.APPLICATION)
                                                .build()
                                )
                                .setProfile(
                                        AccountCreateParams.Defaults.Profile.builder()
                                                .setBusinessUrl(stripeConnectProperties.businessUrl())
                                                .setProductDescription(
                                                        stripeConnectProperties.productDescription())
                                                .build()
                                )
                                .build()
                )
                .setConfiguration(
                        AccountCreateParams.Configuration.builder()
                                .setRecipient(
                                        AccountCreateParams.Configuration.Recipient.builder()
                                                .setCapabilities(
                                                        AccountCreateParams.Configuration.Recipient
                                                                .Capabilities.builder()
                                                                .setStripeBalance(
                                                                        stripeBalanceCapabilities())
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                )
                .putMetadata("user_id", user.getId().toString())
                .addInclude(AccountCreateParams.Include.CONFIGURATION__RECIPIENT)
                .build();

        return stripeGateway.createAccountV2(params).getId();
    }

    /**
     * Identite du compte : pays et type toujours ; pour un particulier, le nom legal en plus.
     * La creation de compte etant fermee tant que l'identite n'est pas verifiee
     * ({@code kyc-required}), le snapshot existe au moment ou ce code s'execute ; s'il manque
     * malgre tout (session purgee, reseau), le compte se cree sans prefill et Stripe
     * redemande — jamais d'echec pour un prefill.
     *
     * <p>Le nom est le seul champ preremli. Date de naissance et adresse de residence sont
     * demandees par le formulaire Connect lui-meme : il les revalide de toute facon, et les
     * envoyer d'avance n'evitait aucune saisie tout en ouvrant une classe d'echecs — une
     * adresse au format ou au pays inattendu faisait rejeter la creation entiere
     * ({@code address_country_identity_country}).
     *
     * <p>Un compte pro reste sans prefill : l'identite verifiee est celle de la personne,
     * pas de la societe ({@code entity_type: company}).
     */
    private AccountCreateParams.Identity buildIdentity(UserEntity user, String country) {
        AccountCreateParams.Identity.Builder identity = AccountCreateParams.Identity.builder()
                .setCountry(country)
                .setEntityType(
                        user.isProAccount()
                                ? AccountCreateParams.Identity.EntityType.COMPANY
                                : AccountCreateParams.Identity.EntityType.INDIVIDUAL);

        if (!user.isProAccount()) {
            VerifiedIdentitySnapshot snapshot =
                    verifiedIdentity.forUser(user.getId()).orElse(null);
            AccountCreateParams.Identity.Individual individual =
                    buildIndividual(user, snapshot);
            if (individual != null) {
                identity.setIndividual(individual);
            }
        }
        return identity.build();
    }

    /**
     * Deux sources, une priorite : ce que Stripe Identity a <em>verifie</em> prime sur ce que
     * l'utilisateur a <em>declare</em> a l'inscription. Un nom verifie sur piece d'identite vaut
     * mieux qu'un nom tape au clavier, et c'est celui que Stripe recoupera de son cote.
     *
     * <p>Le declaratif n'est donc pas un doublon mais un filet : il couvre le cas ou les
     * verified_outputs manquent (session purgee, champ absent du document, panne reseau au moment
     * du provisioning). Rend {@code null} quand aucune source n'a rien a donner — un
     * {@code individual} vide serait refuse par Stripe.
     */
    private AccountCreateParams.Identity.Individual buildIndividual(UserEntity user,
                                                                    VerifiedIdentitySnapshot snapshot) {
        String givenName = firstNonBlank(
                snapshot != null ? snapshot.givenName() : null, user.getFirstName());
        String surname = firstNonBlank(
                snapshot != null ? snapshot.surname() : null, user.getLastName());
        if (givenName == null && surname == null) {
            return null;
        }

        AccountCreateParams.Identity.Individual.Builder individual =
                AccountCreateParams.Identity.Individual.builder();
        if (givenName != null) {
            individual.setGivenName(givenName);
        }
        if (surname != null) {
            individual.setSurname(surname);
        }
        return individual.build();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }

    /**
     * Chemin US / Canada : compte {@code merchant} (card_payments) + {@code recipient}
     * (stripe_transfers), créé via un account token.
     *
     * <p>Le token ne porte que l'email de contact, le type d'entité et, pour un
     * particulier, le nom — même préfill minimal que le chemin recipient-only. L'identité
     * complète (SSN aux US, adresse, activité) est collectée par l'onboarding hébergé
     * Express : elle transite directement du voyageur vers Stripe, jamais par nos
     * serveurs (conformité PSD2, raison d'être de l'exigence de token).
     *
     * <p>Sur la création du compte, seuls restent : le pays (interdit dans un token),
     * les configurations, les defaults et les metadata. Poser {@code contact_email} ou
     * {@code identity.entity_type} à côté du token est refusé
     * ({@code param_alongside_account_token}). Chaîne complète validée en test mode le
     * 2026-09-03 (US et CA, account link d'onboarding compris).
     */
    private String provisionMerchantAndRecipient(UserEntity user, String country)
            throws StripeException {
        AccountTokenCreateParams.Identity.Builder tokenIdentity =
                AccountTokenCreateParams.Identity.builder()
                        .setEntityType(
                                user.isProAccount()
                                        ? AccountTokenCreateParams.Identity.EntityType.COMPANY
                                        : AccountTokenCreateParams.Identity.EntityType.INDIVIDUAL);

        if (!user.isProAccount()) {
            VerifiedIdentitySnapshot snapshot =
                    verifiedIdentity.forUser(user.getId()).orElse(null);
            AccountTokenCreateParams.Identity.Individual individual =
                    buildTokenIndividual(user, snapshot);
            if (individual != null) {
                tokenIdentity.setIndividual(individual);
            }
        }

        AccountTokenCreateParams tokenParams = AccountTokenCreateParams.builder()
                .setContactEmail(firebaseContact.getContact(user.getFirebaseUid()).email())
                .setIdentity(tokenIdentity.build())
                .build();
        String accountToken = stripeGateway.createAccountToken(tokenParams).getId();

        AccountCreateParams params = AccountCreateParams.builder()
                .setAccountToken(accountToken)
                .setDashboard(AccountCreateParams.Dashboard.EXPRESS)
                .setIdentity(AccountCreateParams.Identity.builder()
                        .setCountry(country)
                        .build())
                .setDefaults(
                        AccountCreateParams.Defaults.builder()
                                .setResponsibilities(
                                        AccountCreateParams.Defaults.Responsibilities.builder()
                                                .setLossesCollector(
                                                        AccountCreateParams.Defaults.Responsibilities
                                                                .LossesCollector.APPLICATION)
                                                .setFeesCollector(
                                                        AccountCreateParams.Defaults.Responsibilities
                                                                .FeesCollector.APPLICATION)
                                                .build())
                                .setProfile(
                                        AccountCreateParams.Defaults.Profile.builder()
                                                .setBusinessUrl(stripeConnectProperties.businessUrl())
                                                .setProductDescription(
                                                        stripeConnectProperties.productDescription())
                                                .build())
                                .build())
                .setConfiguration(
                        AccountCreateParams.Configuration.builder()
                                .setMerchant(
                                        AccountCreateParams.Configuration.Merchant.builder()
                                                .setCapabilities(
                                                        AccountCreateParams.Configuration.Merchant
                                                                .Capabilities.builder()
                                                                .setCardPayments(
                                                                        AccountCreateParams.Configuration
                                                                                .Merchant.Capabilities
                                                                                .CardPayments.builder()
                                                                                .setRequested(true)
                                                                                .build())
                                                                .build())
                                                .build())
                                .setRecipient(
                                        AccountCreateParams.Configuration.Recipient.builder()
                                                .setCapabilities(
                                                        AccountCreateParams.Configuration.Recipient
                                                                .Capabilities.builder()
                                                                .setStripeBalance(
                                                                        stripeBalanceCapabilities())
                                                                .build())
                                                .build())
                                .build())
                .putMetadata("user_id", user.getId().toString())
                .addInclude(AccountCreateParams.Include.CONFIGURATION__RECIPIENT)
                .build();

        return stripeGateway.createAccountV2(params).getId();
    }

    /** Variante token de {@link #buildIndividual} — mêmes sources, même priorité. */
    private AccountTokenCreateParams.Identity.Individual buildTokenIndividual(
            UserEntity user, VerifiedIdentitySnapshot snapshot) {
        String givenName = firstNonBlank(
                snapshot != null ? snapshot.givenName() : null, user.getFirstName());
        String surname = firstNonBlank(
                snapshot != null ? snapshot.surname() : null, user.getLastName());
        if (givenName == null && surname == null) {
            return null;
        }

        AccountTokenCreateParams.Identity.Individual.Builder individual =
                AccountTokenCreateParams.Identity.Individual.builder();
        if (givenName != null) {
            individual.setGivenName(givenName);
        }
        if (surname != null) {
            individual.setSurname(surname);
        }
        return individual.build();
    }

    /**
     * Seul {@code stripe_transfers} se demande. {@code payouts} n'est pas un parametre de
     * creation : c'est un statut en lecture seule dans la reponse, et l'API le rejette
     * ("Unknown field") si on tente de le poser.
     */
    private AccountCreateParams.Configuration.Recipient.Capabilities.StripeBalance
            stripeBalanceCapabilities() {
        return AccountCreateParams.Configuration.Recipient.Capabilities.StripeBalance.builder()
                .setStripeTransfers(
                        AccountCreateParams.Configuration.Recipient.Capabilities.StripeBalance
                                .StripeTransfers.builder()
                                .setRequested(true)
                                .build()
                )
                .build();
    }
}
