package com.yadony.api.payments.cash;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;

/**
 * Seam over the Stripe SDK statics used by {@link CashCommissionService}, so its
 * commission/setup logic can run in E2E without a live Stripe connection. Production uses
 * {@link StripeCashGatewayImpl} — strict passthrough, behaviour unchanged.
 */
public interface StripeCashGateway {

    SetupIntent createSetupIntent(SetupIntentCreateParams params) throws StripeException;

    SetupIntent retrieveSetupIntent(String setupIntentId) throws StripeException;

    PaymentMethod retrievePaymentMethod(String paymentMethodId) throws StripeException;

    Customer createCustomer(CustomerCreateParams params) throws StripeException;

    PaymentIntent createPaymentIntent(PaymentIntentCreateParams params, RequestOptions options) throws StripeException;

    PaymentIntent retrievePaymentIntent(String paymentIntentId) throws StripeException;

    PaymentIntent cancelPaymentIntent(String paymentIntentId) throws StripeException;

    Refund createRefund(RefundCreateParams params, RequestOptions options) throws StripeException;
}
