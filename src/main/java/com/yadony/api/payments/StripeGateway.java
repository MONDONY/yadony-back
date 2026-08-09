package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Customer;
import com.stripe.model.EphemeralKey;
import com.stripe.model.PaymentIntent;
import com.stripe.model.identity.VerificationSession;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.EphemeralKeyCreateParams;
import com.stripe.param.PaymentIntentCreateParams;

/**
 * Thin seam over the Stripe SDK's static entry points so the surrounding business logic
 * in {@link PaymentService} can be exercised without a live Stripe connection (the e2e
 * profile injects a stub implementation). Production uses {@link StripeGatewayImpl}, whose
 * methods are 1:1 wrappers over the SDK statics — behaviour is unchanged.
 */
public interface StripeGateway {

    Account createAccount(AccountCreateParams params) throws StripeException;

    Account retrieveAccount(String accountId) throws StripeException;

    AccountLink createAccountLink(AccountLinkCreateParams params) throws StripeException;

    PaymentIntent createPaymentIntent(PaymentIntentCreateParams params) throws StripeException;

    PaymentIntent retrievePaymentIntent(String paymentIntentId) throws StripeException;

    PaymentIntent capturePaymentIntent(PaymentIntent paymentIntent) throws StripeException;

    Customer createCustomer(CustomerCreateParams params) throws StripeException;

    EphemeralKey createEphemeralKey(EphemeralKeyCreateParams params) throws StripeException;

    /**
     * Retrieves a Stripe Identity verification session with {@code verified_outputs} expanded,
     * so the caller can reuse already-verified identity data (name, DOB, address, ID number)
     * without asking the user to type it again — e.g. to prefill Connect onboarding.
     */
    VerificationSession retrieveVerificationSession(String sessionId) throws StripeException;
}
