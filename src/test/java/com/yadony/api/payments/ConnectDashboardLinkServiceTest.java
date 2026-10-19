package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.LoginLink;
import com.stripe.param.AccountLinkCreateParams;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.StripeConnectProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ConnectDashboardLinkServiceTest {

    @Mock UserRepository userRepository;
    @Mock StripeGateway stripeGateway;
    ConnectDashboardLinkService service;
    final UserEntity user = new UserEntity();

    @BeforeEach
    void setUp() {
        service = new ConnectDashboardLinkService(userRepository, stripeGateway, new StripeConnectProperties(
                null, null, null, "https://api.test/return", "https://api.test/refresh", null, null));
        user.setFirebaseUid("uid");
        when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(user));
    }

    private static Account account(Boolean detailsSubmitted) {
        Account account = new Account();
        account.setDetailsSubmitted(detailsSubmitted);
        return account;
    }

    private static YadonyBusinessException problem(Runnable call) {
        try {
            call.run();
        } catch (YadonyBusinessException e) {
            return e;
        }
        throw new AssertionError("exception attendue");
    }

    @Test
    void onboardedAccount_getsExpressLoginLink() throws Exception {
        user.setStripeAccountId("acct_1");
        when(stripeGateway.retrieveAccount("acct_1")).thenReturn(account(true));
        LoginLink link = new LoginLink();
        link.setUrl("https://connect.stripe.com/express/abc");
        when(stripeGateway.createLoginLink("acct_1")).thenReturn(link);

        assertThat(service.createDashboardLink("uid").url()).isEqualTo("https://connect.stripe.com/express/abc");
        verify(stripeGateway, never()).createAccountLink(any());
    }

    @Test
    void noConnectAccount_is404() {
        YadonyBusinessException e = problem(() -> service.createDashboardLink("uid"));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.getErrorCode()).isEqualTo("stripe-account-missing");

        user.setStripeAccountId(" ");
        assertThat(problem(() -> service.createDashboardLink("uid")).getErrorCode()).isEqualTo("stripe-account-missing");
    }

    @Test
    void accountDeletedAtStripe_is404() throws Exception {
        user.setStripeAccountId("acct_gone");
        when(stripeGateway.retrieveAccount("acct_gone")).thenThrow(
                new InvalidRequestException("No such account", "account", "req", "resource_missing", 404, null));
        assertThat(problem(() -> service.createDashboardLink("uid")).getErrorCode()).isEqualTo("stripe-account-missing");
    }

    @Test
    void onboardingNotFinished_is409() throws Exception {
        user.setStripeAccountId("acct_1");
        when(stripeGateway.retrieveAccount("acct_1")).thenReturn(account(false));
        YadonyBusinessException e = problem(() -> service.createDashboardLink("uid"));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("stripe-onboarding-incomplete");
        verify(stripeGateway, never()).createLoginLink(anyString());
    }

    @Test
    void loginLinkRefused_fallsBackToAccountUpdateLink() throws Exception {
        user.setStripeAccountId("acct_1");
        when(stripeGateway.retrieveAccount("acct_1")).thenReturn(account(true));
        when(stripeGateway.createLoginLink("acct_1")).thenThrow(
                new InvalidRequestException("not express", null, "req", "invalid_request", 400, null));
        AccountLink link = new AccountLink();
        link.setUrl("https://connect.stripe.com/setup/e/update");
        when(stripeGateway.createAccountLink(any())).thenReturn(link);

        assertThat(service.createDashboardLink("uid").url()).isEqualTo("https://connect.stripe.com/setup/e/update");
        ArgumentCaptor<AccountLinkCreateParams> params = ArgumentCaptor.forClass(AccountLinkCreateParams.class);
        verify(stripeGateway).createAccountLink(params.capture());
        assertThat(params.getValue().getType()).isEqualTo(AccountLinkCreateParams.Type.ACCOUNT_UPDATE);
        assertThat(params.getValue().getAccount()).isEqualTo("acct_1");
    }

    @Test
    void stripeErrors_are502() throws Exception {
        user.setStripeAccountId("acct_1");
        when(stripeGateway.retrieveAccount("acct_1")).thenThrow(new ApiConnectionException("down"));
        assertThat(problem(() -> service.createDashboardLink("uid")).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void loginLinkNetworkError_is502_andFallbackFailure_is502() throws Exception {
        user.setStripeAccountId("acct_1");
        when(stripeGateway.retrieveAccount("acct_1")).thenReturn(account(true));
        when(stripeGateway.createLoginLink("acct_1")).thenThrow(new ApiConnectionException("down"));
        assertThat(problem(() -> service.createDashboardLink("uid")).getErrorCode())
                .isEqualTo("stripe-dashboard-link-failed");

        org.mockito.Mockito.doThrow(new InvalidRequestException("no", null, "req", "invalid_request", 400, null))
                .when(stripeGateway).createLoginLink("acct_1");
        when(stripeGateway.createAccountLink(any())).thenThrow(new ApiConnectionException("down"));
        assertThat(problem(() -> service.createDashboardLink("uid")).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void unknownUser_is404() {
        when(userRepository.findByFirebaseUid("other")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createDashboardLink("other"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting("errorCode").isEqualTo("user-not-found");
    }
}
