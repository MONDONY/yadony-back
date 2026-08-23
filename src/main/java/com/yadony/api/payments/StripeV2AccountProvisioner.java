package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.param.v2.core.AccountCreateParams;
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
 * <p>Le compte ne porte que la configuration {@code recipient}. C'est délibéré :
 * <ul>
 *   <li>le modèle de paiement est en <em>separate charges and transfers</em> — les fonds
 *       restent sur le solde plateforme et le voyageur est réglé par
 *       {@code Transfer.create} à la livraison, ce qui ne requiert que
 *       {@code stripe_balance.stripe_transfers} ;</li>
 *   <li>ajouter la configuration {@code merchant} déclencherait
 *       {@code account_token_required} — une plateforme établie en France ne peut pas
 *       écrire l'identité sur une configuration marchande sans passer par les
 *       <em>account tokens</em> ;</li>
 *   <li>l'onboarding du voyageur reste réduit au strict nécessaire (identité + IBAN),
 *       sans la vérification marchande qui ne sert à rien dans ce modèle.</li>
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

        // yadony dessert des pays que Stripe ne couvre pas (zone XOF, zone XAF, US, CA).
        // Sans cette garde, Stripe repond une erreur generique remontee en 500 : le
        // voyageur ne comprend pas qu'il doit simplement rester en especes.
        if (!StripeConnectCountries.isSupported(country)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "country-not-supported-by-stripe", "Country Not Supported",
                    "Le paiement par carte n'est pas encore disponible dans votre pays. "
                            + "Vous pouvez continuer a recevoir vos paiements en especes.");
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
     * Identite du compte : pays et type toujours ; pour un particulier, preremplie avec ce
     * que Stripe Identity a deja verifie (nom, date de naissance) et l'adresse de residence
     * declaree a l'inscription — l'onboarding Connect n'a plus a redemander ce que la
     * verification d'identite vient d'etablir. La creation de compte etant fermee tant que
     * l'identite n'est pas verifiee ({@code kyc-required}), le snapshot existe au moment ou
     * ce code s'execute ; s'il manque malgre tout (session purgee, reseau), le compte se
     * cree sans prefill et Stripe redemande — jamais d'echec pour un prefill.
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
            verifiedIdentity.forUser(user.getId())
                    .ifPresent(snapshot -> identity.setIndividual(buildIndividual(user, snapshot)));
        }
        return identity.build();
    }

    private AccountCreateParams.Identity.Individual buildIndividual(UserEntity user,
                                                                    VerifiedIdentitySnapshot snapshot) {
        AccountCreateParams.Identity.Individual.Builder individual =
                AccountCreateParams.Identity.Individual.builder();

        if (snapshot.givenName() != null) {
            individual.setGivenName(snapshot.givenName());
        }
        if (snapshot.surname() != null) {
            individual.setSurname(snapshot.surname());
        }
        if (snapshot.hasDob()) {
            individual.setDateOfBirth(
                    AccountCreateParams.Identity.Individual.DateOfBirth.builder()
                            .setDay(snapshot.dobDay())
                            .setMonth(snapshot.dobMonth())
                            .setYear(snapshot.dobYear())
                            .build());
        }

        buildAddress(user, snapshot).ifPresent(individual::setAddress);
        return individual.build();
    }

    /**
     * L'adresse de residence declaree a l'inscription prime : c'est precisement pour
     * "preparer tes paiements" qu'elle a ete collectee, et elle est plus fraiche que celle
     * du document d'identite. Sa ville vit sur users.city (le formulaire d'adresse du
     * parcours y ecrit) et part avec elle. A defaut (etape passee), l'adresse du document
     * sert de repli quand elle existe.
     */
    private java.util.Optional<AccountCreateParams.Identity.Individual.Address> buildAddress(
            UserEntity user, VerifiedIdentitySnapshot snapshot) {
        String residenceStreet = user.getResidenceStreet();
        if (residenceStreet != null && !residenceStreet.isBlank()) {
            AccountCreateParams.Identity.Individual.Address.Builder address =
                    AccountCreateParams.Identity.Individual.Address.builder()
                            .setLine1(residenceStreet)
                            .setCountry(user.getCountry());
            if (user.getResidenceLine2() != null && !user.getResidenceLine2().isBlank()) {
                address.setLine2(user.getResidenceLine2());
            }
            if (user.getResidencePostalCode() != null && !user.getResidencePostalCode().isBlank()) {
                address.setPostalCode(user.getResidencePostalCode());
            }
            if (user.getCity() != null && !user.getCity().isBlank()) {
                address.setCity(user.getCity());
            }
            return java.util.Optional.of(address.build());
        }

        if (!snapshot.hasAddress()) {
            return java.util.Optional.empty();
        }
        AccountCreateParams.Identity.Individual.Address.Builder address =
                AccountCreateParams.Identity.Individual.Address.builder()
                        .setLine1(snapshot.addressLine1());
        if (snapshot.addressLine2() != null) {
            address.setLine2(snapshot.addressLine2());
        }
        if (snapshot.addressCity() != null) {
            address.setCity(snapshot.addressCity());
        }
        if (snapshot.addressPostalCode() != null) {
            address.setPostalCode(snapshot.addressPostalCode());
        }
        if (snapshot.addressCountry() != null) {
            address.setCountry(snapshot.addressCountry());
        }
        return java.util.Optional.of(address.build());
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
