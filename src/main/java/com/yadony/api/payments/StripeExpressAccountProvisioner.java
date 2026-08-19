package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.param.AccountCreateParams;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.StripeConnectProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Implémentation v1 (Accounts API historique, {@code Type.EXPRESS}) de
 * {@link ConnectAccountProvisioner}. Extraction verbatim du bloc qui vivait
 * auparavant dans {@code PaymentService.createConnectAccount} — aucun
 * changement de comportement, seulement de localisation (lot 4, préparation
 * Accounts v2, ne bascule rien).
 */
@Component
public class StripeExpressAccountProvisioner implements ConnectAccountProvisioner {

    private final StripeGateway stripeGateway;
    private final StripeConnectProperties stripeConnectProperties;
    private final FirebaseContactService firebaseContact;

    public StripeExpressAccountProvisioner(StripeGateway stripeGateway,
                                            StripeConnectProperties stripeConnectProperties,
                                            FirebaseContactService firebaseContact) {
        this.stripeGateway = stripeGateway;
        this.stripeConnectProperties = stripeConnectProperties;
        this.firebaseContact = firebaseContact;
    }

    @Override
    public Account provision(UserEntity user) throws StripeException {
        // Le pays d'un compte Connect est immuable apres creation : mieux vaut
        // refuser que fabriquer un compte francais par defaut, comme le faisait
        // le "FR" en dur de UserEntity avant le 2026-08-19.
        if (user.getCountry() == null || user.getCountry().isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "country-required", "Country Required",
                    "Renseignez votre pays dans les reglages avant de creer votre "
                            + "compte de paiement.");
        }

        AccountCreateParams params = AccountCreateParams.builder()
                .setType(AccountCreateParams.Type.EXPRESS)
                .setCountry(user.getCountry())
                .setEmail(firebaseContact.getContact(user.getFirebaseUid()).email())
                .setBusinessType(
                        user.isProAccount()
                                ? AccountCreateParams.BusinessType.COMPANY
                                : AccountCreateParams.BusinessType.INDIVIDUAL
                )
                .setCapabilities(
                        AccountCreateParams.Capabilities.builder()
                                .setCardPayments(
                                        AccountCreateParams.Capabilities.CardPayments.builder()
                                                .setRequested(true) // historiquement requis pour on_behalf_of (retiré) — conservé, no-op
                                                .build()
                                )
                                .setTransfers(
                                        AccountCreateParams.Capabilities.Transfers.builder()
                                                .setRequested(true)
                                                .build()
                                )
                                .build()
                )
                .setBusinessProfile(
                        AccountCreateParams.BusinessProfile.builder()
                                .setMcc(stripeConnectProperties.mcc())
                                .setProductDescription(stripeConnectProperties.productDescription())
                                .setUrl(stripeConnectProperties.businessUrl())
                                .build()
                )
                .setSettings(
                        AccountCreateParams.Settings.builder()
                                .setPayouts(
                                        AccountCreateParams.Settings.Payouts.builder()
                                                .setSchedule(
                                                        AccountCreateParams.Settings.Payouts.Schedule.builder()
                                                                .setInterval(AccountCreateParams.Settings.Payouts.Schedule.Interval.DAILY)
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                )
                .putMetadata("user_id", user.getId().toString())
                .build();

        return stripeGateway.createAccount(params);
    }
}
