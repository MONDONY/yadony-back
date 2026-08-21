package com.yadony.api.payments;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.stripe.model.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Orchestration de {@code createConnectAccount} — verrou, relecture, sauvegarde,
 * audit. La construction des paramètres Stripe elle-même est extraite dans
 * {@link StripeV2AccountProvisioner}, couverte par
 * {@link StripeV2AccountProvisionerTest}.
 */
@ExtendWith(MockitoExtension.class)
class StripeConnectAccountCreationTest {

    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ConnectAccountProvisioner connectAccountProvisioner;

    PaymentService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PaymentService(
                userRepository, bidRepository, mock(com.yadony.api.matching.BidGridItemRepository.class), announcementRepository,
                paymentRepository, auditService, eventPublisher,
                PaymentServiceTestFactory.defaultConnectProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                org.mockito.Mockito.mock(com.yadony.api.common.stripe.AdminAlertService.class), PaymentServiceTestFactory.stubbedResolver(), org.mockito.Mockito.mock(com.yadony.api.promo.PromoService.class), new StripeGatewayImpl(null),
                PaymentServiceTestFactory.stubbedContacts(), mock(com.yadony.api.voucher.CommissionVoucherService.class), connectAccountProvisioner
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
    void createConnectAccount_delegatesToProvisioner_andPersistsReturnedAccountId() {
        UserEntity user = buildUser(false, "FR");
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        try {
            when(connectAccountProvisioner.provision(user)).thenReturn("acct_new");
        } catch (Exception ignored) { /* checked StripeException, never thrown in this stub */ }

        service.createConnectAccount("uid-test");

        assertThat(user.getStripeAccountId()).isEqualTo("acct_new");
        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.PENDING_ONBOARDING);
        assertThat(user.getStripeAccountCreatedAt()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    void createConnectAccount_auditsAccountCreation() {
        UserEntity user = buildUser(false, "FR");
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        try {
            when(connectAccountProvisioner.provision(user)).thenReturn("acct_audit");
        } catch (Exception ignored) { }

        service.createConnectAccount("uid-test");

        verify(auditService).log(eq("USER"), eq(userId), eq("STRIPE_ACCOUNT_CREATED"), eq(userId), any());
    }

    @Test
    void createConnectAccount_existingAccountVerifiedInStripe_skipsProvisioning() throws Exception {
        UserEntity user = buildUser(false, "FR");
        user.setStripeAccountId("acct_existing");
        user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));

        try (org.mockito.MockedStatic<com.stripe.model.Account> acctStatic =
                     mockStatic(com.stripe.model.Account.class)) {
            acctStatic.when(() -> com.stripe.model.Account.retrieve("acct_existing"))
                    .thenReturn(mock(Account.class));

            service.createConnectAccount("uid-test");

            verifyNoInteractions(connectAccountProvisioner);
        }
    }

    // ── Eligibilite du pays exposee a l'application ──────────────────────────

    @Test
    void getConnectAccountStatus_supportedCountry_reportsConnectAvailable() {
        UserEntity user = buildUser(false, "FR");
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));

        assertThat(service.getConnectAccountStatus("uid-test").connectAvailableInCountry())
                .isTrue();
    }

    @Test
    void getConnectAccountStatus_countryNotCoveredByStripe_reportsConnectUnavailable() {
        // Le Senegal est desservi par yadony mais Stripe n'y ouvre pas de compte : le
        // voyageur doit rester en especes, sans se voir proposer l'activation carte.
        UserEntity user = buildUser(false, "SN");
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));

        assertThat(service.getConnectAccountStatus("uid-test").connectAvailableInCountry())
                .isFalse();
    }

    @Test
    void getConnectAccountStatus_countryNotSet_reportsConnectUnavailable() {
        UserEntity user = buildUser(false, null);
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));

        assertThat(service.getConnectAccountStatus("uid-test").connectAvailableInCountry())
                .isFalse();
    }

    @Test
    void createConnectAccount_reportsConnectAvailabilityAlongsideNewAccount() throws Exception {
        UserEntity user = buildUser(false, "FR");
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(connectAccountProvisioner.provision(user)).thenReturn("acct_new");

        assertThat(service.createConnectAccount("uid-test").connectAvailableInCountry())
                .isTrue();
    }
}
