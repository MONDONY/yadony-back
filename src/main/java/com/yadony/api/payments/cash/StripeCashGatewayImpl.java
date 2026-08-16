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
import org.springframework.stereotype.Component;

/**
 * Production {@link StripeCashGateway}: each method is a direct passthrough to the Stripe SDK
 * static it replaces.
 */
@Component
public class StripeCashGatewayImpl implements StripeCashGateway {

    @Override
    public SetupIntent createSetupIntent(SetupIntentCreateParams params) throws StripeException {
        return SetupIntent.create(params);
    }

    @Override
    public SetupIntent retrieveSetupIntent(String setupIntentId) throws StripeException {
        return SetupIntent.retrieve(setupIntentId);
    }

    @Override
    public PaymentMethod retrievePaymentMethod(String paymentMethodId) throws StripeException {
        return PaymentMethod.retrieve(paymentMethodId);
    }

    @Override
    public Customer createCustomer(CustomerCreateParams params) throws StripeException {
        return Customer.create(params);
    }

    @Override
    public PaymentIntent createPaymentIntent(PaymentIntentCreateParams params, RequestOptions options)
            throws StripeException {
        return PaymentIntent.create(params, options);
    }

    @Override
    public PaymentIntent retrievePaymentIntent(String paymentIntentId) throws StripeException {
        return PaymentIntent.retrieve(paymentIntentId);
    }

    @Override
    public PaymentIntent cancelPaymentIntent(String paymentIntentId) throws StripeException {
        return PaymentIntent.retrieve(paymentIntentId).cancel();
    }

    @Override
    public Refund createRefund(RefundCreateParams params, RequestOptions options) throws StripeException {
        return Refund.create(params, options);
    }
}
