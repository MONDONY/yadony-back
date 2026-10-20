package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.AccountLink;
import com.stripe.param.AccountLinkCreateParams;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.payments.StripeOnboardingResumeService.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StripeOnboardingResumeServiceTest {

    private static final String REFRESH = "https://api.yadony.com/api/v1/payments/onboarding/resume?t=abc";
    private static final String RETURN = "https://api.yadony.com/api/v1/payments/onboarding/resume/done";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final StripeGateway stripeGateway = mock(StripeGateway.class);
    private final AuditService auditService = mock(AuditService.class);
    private final StripeOnboardingResumeService service =
            new StripeOnboardingResumeService(userRepository, stripeGateway, auditService);
    private final UUID userId = UUID.randomUUID();
    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setStripeAccountId("acct_123");
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void pendingOnboarding_redirectsToAFreshStripeLink() throws Exception {
        AccountLink link = new AccountLink();
        link.setUrl("https://connect.stripe.com/setup/e/acct_123/xyz");
        when(stripeGateway.createAccountLink(any())).thenReturn(link);

        Outcome outcome = service.resume(userId, REFRESH, RETURN);

        assertThat(outcome).isEqualTo(new Outcome.Redirect("https://connect.stripe.com/setup/e/acct_123/xyz"));
        ArgumentCaptor<AccountLinkCreateParams> params = ArgumentCaptor.forClass(AccountLinkCreateParams.class);
        verify(stripeGateway).createAccountLink(params.capture());
        assertThat(params.getValue().getAccount()).isEqualTo("acct_123");
        assertThat(params.getValue().getRefreshUrl()).isEqualTo(REFRESH);
        assertThat(params.getValue().getReturnUrl()).isEqualTo(RETURN);
        assertThat(params.getValue().getType()).isEqualTo(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING);
        verify(auditService).log("USER", userId, "STRIPE_ONBOARDING_RESUMED_FROM_EMAIL", userId, Map.of());
    }

    @Test
    void onboardingAlreadyComplete_saysSo() {
        user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);

        assertThat(service.resume(userId, REFRESH, RETURN)).isInstanceOf(Outcome.AlreadyComplete.class);
        verifyNoInteractions(stripeGateway);
    }

    @Test
    void unknownOrInactiveUser_opensTheApp() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        assertThat(service.resume(userId, REFRESH, RETURN)).isInstanceOf(Outcome.OpenApp.class);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        user.setStatus(UserStatus.SUSPENDED);
        assertThat(service.resume(userId, REFRESH, RETURN)).isInstanceOf(Outcome.OpenApp.class);

        verifyNoInteractions(stripeGateway);
    }

    @Test
    void noAccount_otherStatus_orUnverifiedIdentity_opensTheApp() {
        user.setStripeAccountId(null);
        assertThat(service.resume(userId, REFRESH, RETURN)).isInstanceOf(Outcome.OpenApp.class);

        user.setStripeAccountId("acct_123");
        user.setStripeAccountStatus(StripeAccountStatus.DISABLED);
        assertThat(service.resume(userId, REFRESH, RETURN)).isInstanceOf(Outcome.OpenApp.class);

        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        user.setKycStatus(KycStatus.REJECTED);
        assertThat(service.resume(userId, REFRESH, RETURN)).isInstanceOf(Outcome.OpenApp.class);

        verifyNoInteractions(stripeGateway);
    }

    @Test
    void stripeFailure_opensTheAppWithoutAudit() throws Exception {
        when(stripeGateway.createAccountLink(any()))
                .thenThrow(new ApiConnectionException("timeout"));

        assertThat(service.resume(userId, REFRESH, RETURN)).isInstanceOf(Outcome.OpenApp.class);
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), anyMap());

        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(stripeGateway).createAccountLink(any());
        assertThat(service.resume(userId, REFRESH, RETURN)).isInstanceOf(Outcome.OpenApp.class);
        verify(auditService, never()).log(anyString(), eq(userId), anyString(), any(), anyMap());
    }
}
