package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.param.v2.core.AccountCreateParams;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.StripeConnectProperties;
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

    public StripeV2AccountProvisioner(StripeGateway stripeGateway,
                                      StripeConnectProperties stripeConnectProperties,
                                      FirebaseContactService firebaseContact) {
        this.stripeGateway = stripeGateway;
        this.stripeConnectProperties = stripeConnectProperties;
        this.firebaseContact = firebaseContact;
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
                .setIdentity(
                        AccountCreateParams.Identity.builder()
                                .setCountry(country)
                                .setEntityType(
                                        user.isProAccount()
                                                ? AccountCreateParams.Identity.EntityType.COMPANY
                                                : AccountCreateParams.Identity.EntityType.INDIVIDUAL
                                )
                                .build()
                )
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
