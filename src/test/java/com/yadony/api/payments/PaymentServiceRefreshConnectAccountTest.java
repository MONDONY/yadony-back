package com.yadony.api.payments;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.dto.ConnectAccountResponse;
import com.stripe.exception.ApiException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceRefreshConnectAccountTest {

    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    PaymentService service;

    private final UUID userId = UUID.randomUUID();
    private static final String FIREBASE_UID = "uid-traveler";
    private static final String ACCT_ID = "acct_test_123";

    @BeforeEach
    void setUp() {
        service = new PaymentService(
                userRepository, bidRepository, mock(com.yadony.api.matching.BidGridItemRepository.class), announcementRepository,
                paymentRepository, auditService, eventPublisher,
                PaymentServiceTestFactory.defaultConnectProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                org.mockito.Mockito.mock(com.yadony.api.common.stripe.AdminAlertService.class), PaymentServiceTestFactory.stubbedResolver(), org.mockito.Mockito.mock(com.yadony.api.promo.PromoService.class), new StripeGatewayImpl(),
                PaymentServiceTestFactory.stubbedContacts(),
                mock(com.yadony.api.kyc.KycRepository.class)
);
    }

    private UserEntity buildUser(String stripeAccountId, boolean onboarded) {
        UserEntity u = new UserEntity();
        try {
            Field f = u.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, userId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        u.setFirebaseUid(FIREBASE_UID);
        u.setStripeAccountId(stripeAccountId);
        if (stripeAccountId != null) {
            u.setStripeAccountStatus(onboarded
                    ? StripeAccountStatus.ONBOARDING_COMPLETE
                    : StripeAccountStatus.PENDING_ONBOARDING);
        }
        return u;
    }

    @Test
    void throws_conflict_when_user_has_no_stripe_account() {
        UserEntity user = buildUser(null, false);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

        Throwable thrown = catchThrowable(() -> service.refreshConnectAccount(FIREBASE_UID));

        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
        assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("stripe-account-required");
        verify(userRepository, never()).save(any());
    }

    @Test
    void flips_onboarded_to_true_when_charges_enabled_and_was_false() throws StripeException {
        UserEntity user = buildUser(ACCT_ID, false);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

        Account account = mock(Account.class);
        when(account.getId()).thenReturn(ACCT_ID);
        when(account.getChargesEnabled()).thenReturn(true);
        when(account.getPayoutsEnabled()).thenReturn(true); // both required for ONBOARDING_COMPLETE

        try (MockedStatic<Account> mocked = mockStatic(Account.class)) {
            mocked.when(() -> Account.retrieve(ACCT_ID)).thenReturn(account);

            ConnectAccountResponse resp = service.refreshConnectAccount(FIREBASE_UID);

            assertThat(resp.stripeAccountId()).isEqualTo(ACCT_ID);
            assertThat(resp.stripeAccountStatus()).isEqualTo(StripeAccountStatus.ONBOARDING_COMPLETE);
            assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.ONBOARDING_COMPLETE);
            verify(userRepository).save(user);
            verify(auditService).log(eq("USER"), eq(userId),
                    eq("STRIPE_ONBOARDING_COMPLETE"), eq(userId), any());
        }
    }

    @Test
    void noop_when_charges_enabled_and_already_onboarded() throws StripeException {
        UserEntity user = buildUser(ACCT_ID, true);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

        Account account = mock(Account.class);
        when(account.getId()).thenReturn(ACCT_ID);
        when(account.getChargesEnabled()).thenReturn(true);
        when(account.getPayoutsEnabled()).thenReturn(true); // both required for ONBOARDING_COMPLETE

        try (MockedStatic<Account> mocked = mockStatic(Account.class)) {
            mocked.when(() -> Account.retrieve(ACCT_ID)).thenReturn(account);

            ConnectAccountResponse resp = service.refreshConnectAccount(FIREBASE_UID);

            assertThat(resp.stripeAccountStatus()).isEqualTo(StripeAccountStatus.ONBOARDING_COMPLETE);
            verify(userRepository, never()).save(any());
            verify(auditService, never()).log(any(), any(), any(), any(), any());
        }
    }

    @Test
    void flips_onboarded_to_false_when_charges_disabled_and_was_true() throws StripeException {
        UserEntity user = buildUser(ACCT_ID, true);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

        Account account = mock(Account.class);
        when(account.getId()).thenReturn(ACCT_ID);
        when(account.getChargesEnabled()).thenReturn(false);

        try (MockedStatic<Account> mocked = mockStatic(Account.class)) {
            mocked.when(() -> Account.retrieve(ACCT_ID)).thenReturn(account);

            ConnectAccountResponse resp = service.refreshConnectAccount(FIREBASE_UID);

            assertThat(resp.stripeAccountStatus()).isEqualTo(StripeAccountStatus.PENDING_ONBOARDING);
            assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.PENDING_ONBOARDING);
            verify(userRepository).save(user);
            verify(auditService).log(eq("USER"), eq(userId),
                    eq("STRIPE_ONBOARDING_REVOKED"), eq(userId), any());
        }
    }

    @Test
    void noop_when_charges_disabled_and_already_not_onboarded() throws StripeException {
        UserEntity user = buildUser(ACCT_ID, false);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

        Account account = mock(Account.class);
        when(account.getId()).thenReturn(ACCT_ID);
        when(account.getChargesEnabled()).thenReturn(false);

        try (MockedStatic<Account> mocked = mockStatic(Account.class)) {
            mocked.when(() -> Account.retrieve(ACCT_ID)).thenReturn(account);

            ConnectAccountResponse resp = service.refreshConnectAccount(FIREBASE_UID);

            assertThat(resp.stripeAccountStatus()).isEqualTo(StripeAccountStatus.PENDING_ONBOARDING);
            verify(userRepository, never()).save(any());
            verify(auditService, never()).log(any(), any(), any(), any(), any());
        }
    }

    @Test
    void requirementsChanged_statusUnchanged_savesWithoutAuditLog() throws StripeException {
        UserEntity user = buildUser(ACCT_ID, true);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

        Account account = mock(Account.class);
        when(account.getId()).thenReturn(ACCT_ID);
        when(account.getChargesEnabled()).thenReturn(true);
        when(account.getPayoutsEnabled()).thenReturn(true);
        Account.Requirements requirements = mock(Account.Requirements.class);
        when(requirements.getCurrentlyDue()).thenReturn(java.util.List.of("individual.verification.document"));
        when(account.getRequirements()).thenReturn(requirements);

        try (MockedStatic<Account> mocked = mockStatic(Account.class)) {
            mocked.when(() -> Account.retrieve(ACCT_ID)).thenReturn(account);

            ConnectAccountResponse resp = service.refreshConnectAccount(FIREBASE_UID);

            assertThat(resp.requirementsCurrentlyDue()).containsExactly("individual.verification.document");
            assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.ONBOARDING_COMPLETE);
            verify(userRepository).save(user);
            verify(auditService, never()).log(any(), any(), any(), any(), any());
        }
    }

    @Test
    void throws_bad_gateway_when_stripe_call_fails() {
        UserEntity user = buildUser(ACCT_ID, false);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

        try (MockedStatic<Account> mocked = mockStatic(Account.class)) {
            mocked.when(() -> Account.retrieve(ACCT_ID))
                    .thenThrow(new ApiException("Stripe down", "req_x", "api_error", 503, null));

            Throwable thrown = catchThrowable(() -> service.refreshConnectAccount(FIREBASE_UID));

            assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
            assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("stripe-refresh-failed");
            verify(userRepository, never()).save(any());
        }
    }
}
