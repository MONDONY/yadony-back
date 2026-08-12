package com.yadony.api.payments;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.stripe.model.Account;
import com.stripe.param.AccountCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for createConnectAccount — verifies that AccountCreateParams
 * are built with correct values from user model and StripeConnectProperties.
 */
@ExtendWith(MockitoExtension.class)
class StripeConnectAccountCreationTest {

    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    PaymentService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PaymentService(
                userRepository, bidRepository, mock(com.yadony.api.matching.BidGridItemRepository.class), announcementRepository,
                paymentRepository, auditService, eventPublisher,
                PaymentServiceTestFactory.defaultConnectProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                org.mockito.Mockito.mock(com.yadony.api.common.stripe.AdminAlertService.class), PaymentServiceTestFactory.stubbedResolver(), org.mockito.Mockito.mock(com.yadony.api.promo.PromoService.class), new StripeGatewayImpl(),
                PaymentServiceTestFactory.stubbedContacts(),
                mock(com.yadony.api.payments.currency.ActiveCurrencyResolver.class, inv -> "EUR"),
                new com.yadony.api.payments.currency.CurrencyMatchGuard()
);
    }

    private UserEntity buildUser(boolean isPro, String country) {
        UserEntity u = new UserEntity();
        PaymentServiceTestFactory.setId(u, userId);
        u.setFirebaseUid("uid-test");
        u.setProAccount(isPro);
        u.setCountry(country);
        // createConnectAccount uses findByIdForUpdate for the pessimistic lock
        lenient().when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void createConnectAccount_nonPro_setsBusinessTypeIndividual() {
        UserEntity user = buildUser(false, "FR");
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAccount = mock(Account.class);
            when(mockAccount.getId()).thenReturn("acct_individual");
            ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
            acctStatic.when(() -> Account.create(captor.capture())).thenReturn(mockAccount);

            service.createConnectAccount("uid-test");

            AccountCreateParams params = captor.getValue();
            assertThat(params.getBusinessType())
                    .isEqualTo(AccountCreateParams.BusinessType.INDIVIDUAL);
        }
    }

    @Test
    void createConnectAccount_pro_setsBusinessTypeCompany() {
        UserEntity user = buildUser(true, "FR");
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAccount = mock(Account.class);
            when(mockAccount.getId()).thenReturn("acct_company");
            ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
            acctStatic.when(() -> Account.create(captor.capture())).thenReturn(mockAccount);

            service.createConnectAccount("uid-test");

            AccountCreateParams params = captor.getValue();
            assertThat(params.getBusinessType())
                    .isEqualTo(AccountCreateParams.BusinessType.COMPANY);
        }
    }

    @Test
    void createConnectAccount_hasCardPaymentsCapabilityRequested() {
        UserEntity user = buildUser(false, "FR");
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAccount = mock(Account.class);
            when(mockAccount.getId()).thenReturn("acct_cap");
            ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
            acctStatic.when(() -> Account.create(captor.capture())).thenReturn(mockAccount);

            service.createConnectAccount("uid-test");

            AccountCreateParams params = captor.getValue();
            assertThat(params.getCapabilities()).isNotNull();
            assertThat(params.getCapabilities().getCardPayments()).isNotNull();
            assertThat(params.getCapabilities().getCardPayments().getRequested()).isTrue();
            assertThat(params.getCapabilities().getTransfers()).isNotNull();
            assertThat(params.getCapabilities().getTransfers().getRequested()).isTrue();
        }
    }

    @Test
    void createConnectAccount_countryComesFromUser_notHardcoded() {
        // User with country SN (Senegal) — should be passed through, not defaulted to FR
        UserEntity user = buildUser(false, "SN");
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAccount = mock(Account.class);
            when(mockAccount.getId()).thenReturn("acct_sn");
            ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
            acctStatic.when(() -> Account.create(captor.capture())).thenReturn(mockAccount);

            service.createConnectAccount("uid-test");

            AccountCreateParams params = captor.getValue();
            assertThat(params.getCountry()).isEqualTo("SN");
        }
    }

    @Test
    void createConnectAccount_businessProfileHasCorrectMccAndDescription() {
        UserEntity user = buildUser(false, "FR");
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAccount = mock(Account.class);
            when(mockAccount.getId()).thenReturn("acct_bp");
            ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
            acctStatic.when(() -> Account.create(captor.capture())).thenReturn(mockAccount);

            service.createConnectAccount("uid-test");

            AccountCreateParams params = captor.getValue();
            assertThat(params.getBusinessProfile()).isNotNull();
            assertThat(params.getBusinessProfile().getMcc()).isEqualTo("4215");
            assertThat(params.getBusinessProfile().getProductDescription())
                    .isEqualTo("Transport de colis entre particuliers via la plateforme Yadony");
            assertThat(params.getBusinessProfile().getUrl()).isEqualTo("https://yadony.app");
        }
    }

    @Test
    void createConnectAccount_payoutScheduleIsDaily() {
        UserEntity user = buildUser(false, "FR");
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAccount = mock(Account.class);
            when(mockAccount.getId()).thenReturn("acct_payout");
            ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
            acctStatic.when(() -> Account.create(captor.capture())).thenReturn(mockAccount);

            service.createConnectAccount("uid-test");

            AccountCreateParams params = captor.getValue();
            assertThat(params.getSettings()).isNotNull();
            assertThat(params.getSettings().getPayouts()).isNotNull();
            assertThat(params.getSettings().getPayouts().getSchedule()).isNotNull();
            assertThat(params.getSettings().getPayouts().getSchedule().getInterval())
                    .isEqualTo(AccountCreateParams.Settings.Payouts.Schedule.Interval.DAILY);
        }
    }

    @Test
    void createConnectAccount_userStatusUpdated_afterAccountCreation() {
        UserEntity user = buildUser(false, "FR");
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Account> acctStatic = mockStatic(Account.class)) {
            Account mockAccount = mock(Account.class);
            when(mockAccount.getId()).thenReturn("acct_new");
            acctStatic.when(() -> Account.create(any(AccountCreateParams.class))).thenReturn(mockAccount);

            service.createConnectAccount("uid-test");

            assertThat(user.getStripeAccountId()).isEqualTo("acct_new");
            assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.PENDING_ONBOARDING);
            assertThat(user.getStripeAccountCreatedAt()).isNotNull();
            verify(userRepository).save(user);
        }
    }
}
