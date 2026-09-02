package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Customer;
import com.stripe.model.EphemeralKey;
import com.stripe.model.PaymentIntent;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.EphemeralKeyCreateParams;
import com.stripe.param.PaymentIntentCreateParams;

/**
 * Thin seam over the Stripe SDK entry points so the surrounding business logic in
 * {@link PaymentService} can be exercised without a live Stripe connection. Production
 * uses {@link StripeGatewayImpl}, whose methods are 1:1 wrappers over the SDK calls —
 * behaviour is unchanged.
 */
public interface StripeGateway {

    /**
     * Crée un compte connecté via l'API Accounts v2 ({@code POST /v2/core/accounts}).
     * L'API v1 équivalente est bloquée par Stripe ({@code v1_accounts_create_blocked}).
     */
    com.stripe.model.v2.core.Account createAccountV2(
            com.stripe.param.v2.core.AccountCreateParams params) throws StripeException;

    /**
     * Crée un account token v2 ({@code POST /v2/core/account_tokens}). Requis pour créer un
     * compte portant la configuration {@code merchant} depuis une plateforme française
     * ({@code account_token_required}) : l'identité et l'email de contact passent par le token,
     * le reste (pays, configurations, defaults, metadata) reste sur la création du compte.
     */
    com.stripe.model.v2.core.AccountToken createAccountToken(
            com.stripe.param.v2.core.AccountTokenCreateParams params) throws StripeException;

    /**
     * Lecture v1, volontairement conservée : elle rend la même structure sur un compte
     * créé en v2 que sur un compte v1, ce dont dépendent le rafraîchissement du statut
     * et les webhooks {@code account.updated} / {@code capability.updated}.
     */
    Account retrieveAccount(String accountId) throws StripeException;

    AccountLink createAccountLink(AccountLinkCreateParams params) throws StripeException;

    PaymentIntent createPaymentIntent(PaymentIntentCreateParams params) throws StripeException;

    PaymentIntent retrievePaymentIntent(String paymentIntentId) throws StripeException;

    PaymentIntent capturePaymentIntent(PaymentIntent paymentIntent) throws StripeException;

    Customer createCustomer(CustomerCreateParams params) throws StripeException;

    EphemeralKey createEphemeralKey(EphemeralKeyCreateParams params) throws StripeException;
}
