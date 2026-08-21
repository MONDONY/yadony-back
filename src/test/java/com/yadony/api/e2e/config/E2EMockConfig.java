package com.yadony.api.e2e.config;

import com.yadony.api.admin.account.AdminAuthService;
import com.yadony.api.auth.FirebaseTokenFilter;
import com.yadony.api.auth.UserLinkerService;
import com.yadony.api.common.StorageService;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.payments.StripeGateway;
import com.yadony.api.payments.cash.StripeCashGateway;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * E2E test wiring:
 * - TestFirebaseTokenFilter: @Bean @Primary replaces the real Firebase filter.
 *   NOT a @Component so it only runs inside the Spring Security filter chain
 *   (avoids the OncePerRequestFilter double-registration 401 bug).
 * - FirebaseTokenFilter servlet registration: disabled so the base bean does not
 *   run as a servlet filter alongside the security chain.
 * - StorageService: Mockito mock (no real S3 in tests).
 */
@TestConfiguration
@Profile("e2e")
public class E2EMockConfig {

    @Bean
    @Primary
    public FirebaseTokenFilter firebaseTokenFilter(UserLinkerService userLinkerService,
                                                   ObjectMapper objectMapper,
                                                   AdminAuthService adminAuthService) {
        return new TestFirebaseTokenFilter(userLinkerService, objectMapper, adminAuthService);
    }

    @Bean
    public FilterRegistrationBean<FirebaseTokenFilter> disableFirebaseFilterServletRegistration(
            FirebaseTokenFilter firebaseTokenFilter) {
        FilterRegistrationBean<FirebaseTokenFilter> reg =
                new FilterRegistrationBean<>(firebaseTokenFilter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    @Primary
    public FirestoreService firestoreService() {
        return Mockito.mock(FirestoreService.class);
    }

    /**
     * Stubs the Stripe gateway so PaymentService's escrow/connect logic runs end-to-end in
     * E2E without a live Stripe connection. The capability is reported "active" so the
     * card_payments check short-circuits; the PaymentIntent carries an id + client secret.
     */
    @Bean
    @Primary
    public StripeGateway stripeGateway() throws StripeException {
        StripeGateway gateway = Mockito.mock(StripeGateway.class);

        Account.Capabilities caps = Mockito.mock(Account.Capabilities.class);
        Mockito.when(caps.getCardPayments()).thenReturn("active");
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getId()).thenReturn("acct_test123");
        Mockito.when(account.getCapabilities()).thenReturn(caps);
        Mockito.when(account.getChargesEnabled()).thenReturn(true);
        Mockito.when(account.getPayoutsEnabled()).thenReturn(true);
        Mockito.when(account.getDetailsSubmitted()).thenReturn(true);

        AccountLink link = Mockito.mock(AccountLink.class);
        Mockito.when(link.getUrl()).thenReturn("https://connect.stripe.test/onboarding");

        PaymentIntent pi = Mockito.mock(PaymentIntent.class);
        Mockito.when(pi.getId()).thenReturn("pi_test123");
        Mockito.when(pi.getClientSecret()).thenReturn("pi_test123_secret_abc");
        Mockito.when(pi.getStatus()).thenReturn("requires_payment_method");

        Customer escrowCustomer = Mockito.mock(Customer.class);
        Mockito.when(escrowCustomer.getId()).thenReturn("cus_escrow_test123");

        // La création passe par Accounts v2 et rend un type distinct ; la lecture reste
        // en v1, qui répond de la même manière sur un compte créé en v2.
        com.stripe.model.v2.core.Account createdAccount =
                Mockito.mock(com.stripe.model.v2.core.Account.class);
        Mockito.when(createdAccount.getId()).thenReturn("acct_test123");

        Mockito.when(gateway.createAccountV2(Mockito.any())).thenReturn(createdAccount);
        Mockito.when(gateway.retrieveAccount(Mockito.anyString())).thenReturn(account);
        Mockito.when(gateway.createAccountLink(Mockito.any())).thenReturn(link);
        Mockito.when(gateway.createPaymentIntent(Mockito.any())).thenReturn(pi);
        Mockito.when(gateway.retrievePaymentIntent(Mockito.anyString())).thenReturn(pi);
        Mockito.when(gateway.capturePaymentIntent(Mockito.any())).thenReturn(pi);
        Mockito.when(gateway.createCustomer(Mockito.any())).thenReturn(escrowCustomer);
        return gateway;
    }

    /** Stubs the cash-commission Stripe gateway (SetupIntent / PaymentMethod / Customer / Refund). */
    @Bean
    @Primary
    public StripeCashGateway stripeCashGateway() throws StripeException {
        StripeCashGateway gw = Mockito.mock(StripeCashGateway.class);

        Customer customer = Mockito.mock(Customer.class);
        Mockito.when(customer.getId()).thenReturn("cus_test123");
        SetupIntent si = Mockito.mock(SetupIntent.class);
        Mockito.when(si.getId()).thenReturn("seti_test123");
        Mockito.when(si.getClientSecret()).thenReturn("seti_test123_secret");
        Mockito.when(si.getStatus()).thenReturn("succeeded");
        Mockito.when(si.getPaymentMethod()).thenReturn("pm_test123");
        PaymentMethod pm = Mockito.mock(PaymentMethod.class);
        Mockito.when(pm.getId()).thenReturn("pm_test123");
        PaymentIntent pi = Mockito.mock(PaymentIntent.class);
        Mockito.when(pi.getId()).thenReturn("pi_cash_test123");
        Mockito.when(pi.getStatus()).thenReturn("succeeded");
        Mockito.when(pi.getClientSecret()).thenReturn("pi_cash_secret");
        Refund refund = Mockito.mock(Refund.class);
        Mockito.when(refund.getId()).thenReturn("re_test123");

        Mockito.when(gw.createCustomer(Mockito.any())).thenReturn(customer);
        Mockito.when(gw.createSetupIntent(Mockito.any())).thenReturn(si);
        Mockito.when(gw.retrieveSetupIntent(Mockito.anyString())).thenReturn(si);
        Mockito.when(gw.retrievePaymentMethod(Mockito.anyString())).thenReturn(pm);
        Mockito.when(gw.createPaymentIntent(Mockito.any(), Mockito.any())).thenReturn(pi);
        Mockito.when(gw.retrievePaymentIntent(Mockito.anyString())).thenReturn(pi);
        Mockito.when(gw.createRefund(Mockito.any(), Mockito.any())).thenReturn(refund);
        return gw;
    }

    @Bean
    @Primary
    public StorageService storageService() {
        StorageService mock = Mockito.mock(StorageService.class);
        Mockito.when(mock.generatePresignedUrl(Mockito.anyString(), Mockito.any()))
               .thenReturn("https://fake-s3.yadony.test/photo.jpg");
        try {
            Mockito.when(mock.uploadFile(Mockito.any(), Mockito.anyString()))
                   .thenReturn("tracking/test/fake-key.jpg");
        } catch (Exception ignored) {}
        return mock;
    }
}
