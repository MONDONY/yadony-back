package com.yadony.api.payments;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Détachement d'un compte Connect en attente au changement de pays de résidence. */
@ExtendWith(MockitoExtension.class)
class PaymentServiceDetachPendingConnectTest {

    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ConnectAccountProvisioner connectAccountProvisioner;

    PaymentService service;

    private final UUID userId = UUID.randomUUID();
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new PaymentService(
                userRepository, bidRepository, mock(com.yadony.api.matching.BidGridItemRepository.class),
                announcementRepository, paymentRepository, auditService, eventPublisher,
                PaymentServiceTestFactory.defaultConnectProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.yadony.api.common.stripe.AdminAlertService.class),
                PaymentServiceTestFactory.stubbedResolver(), mock(com.yadony.api.promo.PromoService.class),
                new StripeGatewayImpl(null), PaymentServiceTestFactory.stubbedContacts(),
                mock(com.yadony.api.voucher.CommissionVoucherService.class), connectAccountProvisioner);
        user = new UserEntity();
        PaymentServiceTestFactory.setId(user, userId);
        user.setStripeAccountId("acct_pending");
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        user.setStripeAccountCreatedAt(Instant.now());
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("Compte toujours en attente : reinitialise sous verrou et audit STRIPE_ACCOUNT_DETACHED")
    void pendingAccount_isResetAndAudited() {
        service.detachPendingConnectAccountForCountryChange(userId, "acct_pending", "FR", "SN");

        assertThat(user.getStripeAccountId()).isNull();
        assertThat(user.getStripeAccountStatus()).isEqualTo(StripeAccountStatus.NOT_CREATED);
        assertThat(user.getStripeAccountCreatedAt()).isNull();
        verify(userRepository).save(user);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(userId), eq("STRIPE_ACCOUNT_DETACHED"), eq(userId),
                payload.capture());
        assertThat(payload.getValue())
                .containsEntry("stripeAccountId", "acct_pending")
                .containsEntry("reason", "RESIDENCE_COUNTRY_CHANGED")
                .containsEntry("previousCountry", "FR")
                .containsEntry("newCountry", "SN");
        verifyNoInteractions(connectAccountProvisioner);
    }

    @Test
    @DisplayName("Premier choix de pays (previous null) : audit accepte la valeur nulle")
    void nullPreviousCountry_isAudited() {
        service.detachPendingConnectAccountForCountryChange(userId, "acct_pending", null, "SN");

        verify(auditService).log(eq("USER"), eq(userId), eq("STRIPE_ACCOUNT_DETACHED"), eq(userId), any());
    }

    @Test
    @DisplayName("Onboarding termine entre-temps : 422 country-locked, rien n'est touche")
    void accountBecameComplete_throwsCountryLocked() {
        user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);

        assertCountryLocked();
    }

    @Test
    @DisplayName("Onboarding deja termine une fois : 422 country-locked")
    void onboardingCompletedAtSet_throwsCountryLocked() {
        user.setStripeOnboardingCompletedAt(Instant.now());

        assertCountryLocked();
    }

    @Test
    @DisplayName("Compte remplace entre-temps : 422 country-locked")
    void otherAccount_throwsCountryLocked() {
        user.setStripeAccountId("acct_other");

        assertCountryLocked();
    }

    @Test
    @DisplayName("Compte deja detache : rien a faire")
    void alreadyDetached_isNoop() {
        user.setStripeAccountId(null);

        service.detachPendingConnectAccountForCountryChange(userId, "acct_pending", "FR", "SN");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void unknownUser_throwsNotFound() {
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detachPendingConnectAccountForCountryChange(
                userId, "acct_pending", "FR", "SN"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void assertCountryLocked() {
        String accountBefore = user.getStripeAccountId();
        StripeAccountStatus statusBefore = user.getStripeAccountStatus();

        assertThatThrownBy(() -> service.detachPendingConnectAccountForCountryChange(
                userId, "acct_pending", "FR", "SN"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> {
                    YadonyBusinessException e = (YadonyBusinessException) ex;
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("country-locked");
                });
        assertThat(user.getStripeAccountId()).isEqualTo(accountBefore);
        assertThat(user.getStripeAccountStatus()).isEqualTo(statusBefore);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }
}
