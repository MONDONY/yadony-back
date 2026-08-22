package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Customer;
import com.stripe.model.EphemeralKey;
import com.stripe.model.PaymentIntent;
import com.stripe.StripeClient;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.EphemeralKeyCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.stereotype.Component;

/**
 * Production {@link StripeGateway}: each method is a direct passthrough to the Stripe SDK
 * call it replaces, so the runtime behaviour of {@link PaymentService} is identical to
 * calling the SDK inline.
 */
@Component
public class StripeGatewayImpl implements StripeGateway {

    /** L'API v2 n'est pas exposée par les points d'entrée statiques : elle exige un client. */
    private final StripeClient stripeClient;

    public StripeGatewayImpl(StripeClient stripeClient) {
        this.stripeClient = stripeClient;
    }

    @Override
    public com.stripe.model.v2.core.Account createAccountV2(
            com.stripe.param.v2.core.AccountCreateParams params) throws StripeException {
        return stripeClient.v2().core().accounts().create(params);
    }

    @Override
    public Account retrieveAccount(String accountId) throws StripeException {
        return Account.retrieve(accountId);
    }

    @Override
    public AccountLink createAccountLink(AccountLinkCreateParams params) throws StripeException {
        return AccountLink.create(params);
    }

    @Override
    public PaymentIntent createPaymentIntent(PaymentIntentCreateParams params) throws StripeException {
        return PaymentIntent.create(params);
    }

    @Override
    public PaymentIntent retrievePaymentIntent(String paymentIntentId) throws StripeException {
        return PaymentIntent.retrieve(paymentIntentId);
    }

    @Override
    public PaymentIntent capturePaymentIntent(PaymentIntent paymentIntent) throws StripeException {
        return paymentIntent.capture();
    }

    @Override
    public Customer createCustomer(CustomerCreateParams params) throws StripeException {
        return Customer.create(params);
    }

    @Override
    public EphemeralKey createEphemeralKey(EphemeralKeyCreateParams params) throws StripeException {
        // stripe-java exige que la version d'API du client mobile soit portée par les params
        // (EphemeralKeyCreateParams.setStripeVersion) — pas par RequestOptions.
        return EphemeralKey.create(params);
    }
}
