package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Balance;
import com.stripe.model.Payout;
import com.stripe.model.StripeCollection;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StripeCustomerServiceTest {

    @Mock StripeGateway stripeGateway;
    @Mock StripeReleaseIntentRepository releaseIntentRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlertService;
    @Mock PlatformTransactionManager transactionManager;

    StripeCustomerService service;

    static final UUID USER_ID = UUID.randomUUID();
    static final String ACCOUNT = "acct_123";

    @BeforeEach
    void setUp() throws Exception {
        service = new StripeCustomerService(stripeGateway, releaseIntentRepository, paymentRepository,
                userRepository, auditService, adminAlertService, transactionManager);
        when(stripeGateway.retrieveConnectedBalance(ACCOUNT)).thenReturn(balance(0L, 0L));
        when(stripeGateway.listConnectedPayouts(eq(ACCOUNT), anyString())).thenReturn(payouts(0));
    }

    private static Balance balance(long available, long pending) {
        Balance.Available a = new Balance.Available();
        a.setAmount(available);
        a.setCurrency("eur");
        Balance.Pending p = new Balance.Pending();
        p.setAmount(pending);
        p.setCurrency("eur");
        Balance balance = new Balance();
        balance.setAvailable(List.of(a));
        balance.setPending(List.of(p));
        return balance;
    }

    private static StripeCollection<Payout> payouts(int count) {
        StripeCollection<Payout> collection = new StripeCollection<>();
        collection.setData(count == 0 ? List.of() : List.of(new Payout()));
        return collection;
    }

    private static UserEntity user(String accountId) {
        UserEntity user = new UserEntity();
        try {
            Field f = user.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, USER_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        user.setStripeAccountId(accountId);
        return user;
    }

    @Test
    void sansCompteConnect_rienAFaire() {
        service.cleanupForUser(user(null));

        verifyNoInteractions(stripeGateway, adminAlertService, auditService);
    }

    @Test
    void soldeNulSansMouvement_supprimeLeCompteEtLeDetache() throws Exception {
        assertThat(service.closeConnectAccount(USER_ID, ACCOUNT))
                .isEqualTo(StripeCustomerService.ConnectCleanup.DELETED);

        verify(stripeGateway).deleteAccount(ACCOUNT);
        verify(stripeGateway, never()).rejectAccount(anyString(), anyString());
        verify(userRepository).clearStripeAccount(USER_ID, ACCOUNT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(USER_ID), eq("STRIPE_CONNECT_ACCOUNT_CLOSED"), isNull(), payload.capture());
        assertThat(payload.getValue()).containsEntry("outcome", "DELETED").containsEntry("stripeAccountId", ACCOUNT);
        verify(adminAlertService, never()).raise(anyString(), anyString(), anyMap());
    }

    @Test
    void cleanupForUser_passeParLaFermeture() throws Exception {
        service.cleanupForUser(user(ACCOUNT));

        verify(stripeGateway).deleteAccount(ACCOUNT);
    }

    @Test
    void suppressionRefusee_rejetteALaPlace() throws Exception {
        when(stripeGateway.deleteAccount(ACCOUNT)).thenThrow(new ApiConnectionException("refus"));

        assertThat(service.closeConnectAccount(USER_ID, ACCOUNT))
                .isEqualTo(StripeCustomerService.ConnectCleanup.REJECTED);

        verify(stripeGateway).rejectAccount(ACCOUNT, StripeCustomerService.REJECT_REASON);
        verify(userRepository).clearStripeAccount(USER_ID, ACCOUNT);
    }

    @Test
    void suppressionEtRejetRefuses_compteLaisseOuvertEtAlerte() throws Exception {
        when(stripeGateway.deleteAccount(ACCOUNT)).thenThrow(new ApiConnectionException("refus"));
        when(stripeGateway.rejectAccount(anyString(), anyString())).thenThrow(new ApiConnectionException("refus"));

        assertThat(service.closeConnectAccount(USER_ID, ACCOUNT))
                .isEqualTo(StripeCustomerService.ConnectCleanup.KEPT);

        verify(userRepository, never()).clearStripeAccount(any(), any());
        verify(adminAlertService).raise(eq(StripeCustomerService.ALERT_CODE), anyString(), anyMap());
        verify(auditService).log(eq("USER"), eq(USER_ID), eq("STRIPE_CONNECT_ACCOUNT_KEPT"), isNull(), anyMap());
    }

    @Test
    void soldeRestant_rienNEstFermeEtAlerte() throws Exception {
        when(stripeGateway.retrieveConnectedBalance(ACCOUNT)).thenReturn(balance(1250L, 0L));

        assertThat(service.closeConnectAccount(USER_ID, ACCOUNT))
                .isEqualTo(StripeCustomerService.ConnectCleanup.KEPT);

        verify(stripeGateway, never()).deleteAccount(anyString());
        verify(stripeGateway, never()).rejectAccount(anyString(), anyString());
        verify(userRepository, never()).clearStripeAccount(any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(adminAlertService).raise(eq(StripeCustomerService.ALERT_CODE), anyString(), context.capture());
        assertThat(context.getValue()).containsEntry("reason", "BALANCE_REMAINING")
                .containsEntry("balances", "available:1250 eur");
    }

    @Test
    void soldeEnAttente_compteAussiCommeSoldeRestant() throws Exception {
        when(stripeGateway.retrieveConnectedBalance(ACCOUNT)).thenReturn(balance(0L, -300L));

        assertThat(service.closeConnectAccount(USER_ID, ACCOUNT))
                .isEqualTo(StripeCustomerService.ConnectCleanup.KEPT);

        verify(stripeGateway, never()).deleteAccount(anyString());
    }

    @Test
    void soldeIllisible_compteLaisseOuvert() throws Exception {
        when(stripeGateway.retrieveConnectedBalance(ACCOUNT)).thenThrow(new ApiConnectionException("timeout"));

        assertThat(service.closeConnectAccount(USER_ID, ACCOUNT))
                .isEqualTo(StripeCustomerService.ConnectCleanup.KEPT);

        verify(stripeGateway, never()).deleteAccount(anyString());
        verify(adminAlertService).raise(eq(StripeCustomerService.ALERT_CODE), anyString(), anyMap());
    }

    @Test
    void versementReserveEnCours_rejetteSansSupprimerEtAlerte() throws Exception {
        when(releaseIntentRepository.existsByDestinationAndStatus(ACCOUNT, StripeReleaseIntentEntity.Status.PENDING))
                .thenReturn(true);

        assertThat(service.closeConnectAccount(USER_ID, ACCOUNT))
                .isEqualTo(StripeCustomerService.ConnectCleanup.REJECTED);

        verify(stripeGateway, never()).deleteAccount(anyString());
        verify(stripeGateway).rejectAccount(ACCOUNT, StripeCustomerService.REJECT_REASON);
        verify(adminAlertService).raise(eq(StripeCustomerService.ALERT_CODE), anyString(), anyMap());
        verify(userRepository).clearStripeAccount(USER_ID, ACCOUNT);
    }

    @Test
    void virementVersLaBanqueEnTransit_rejette() throws Exception {
        when(stripeGateway.listConnectedPayouts(ACCOUNT, "in_transit")).thenReturn(payouts(1));

        assertThat(service.closeConnectAccount(USER_ID, ACCOUNT))
                .isEqualTo(StripeCustomerService.ConnectCleanup.REJECTED);

        verify(stripeGateway, never()).deleteAccount(anyString());
    }

    @Test
    void sequestreActif_rejette() throws Exception {
        when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(true);

        assertThat(service.closeConnectAccount(USER_ID, ACCOUNT))
                .isEqualTo(StripeCustomerService.ConnectCleanup.REJECTED);
    }

    @Test
    void mouvementEnCoursEtRejetRefuse_compteLaisseOuvert() throws Exception {
        when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(true);
        when(stripeGateway.rejectAccount(anyString(), anyString())).thenThrow(new ApiConnectionException("refus"));

        assertThat(service.closeConnectAccount(USER_ID, ACCOUNT))
                .isEqualTo(StripeCustomerService.ConnectCleanup.KEPT);

        verify(userRepository, never()).clearStripeAccount(any(), any());
    }

    @Test
    void auditImpossibleQuandLeCompteResteOuvert_neRemontePas() throws Exception {
        when(stripeGateway.retrieveConnectedBalance(ACCOUNT)).thenReturn(balance(10L, 0L));
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(auditService)
                .log(anyString(), any(), anyString(), any(), anyMap());

        assertThat(service.closeConnectAccount(USER_ID, ACCOUNT))
                .isEqualTo(StripeCustomerService.ConnectCleanup.KEPT);
    }

    @Test
    void nonZeroBalances_toleranceAuxChampsAbsents() {
        assertThat(StripeCustomerService.nonZeroBalances(null)).isEmpty();
        assertThat(StripeCustomerService.nonZeroBalances(new Balance())).isEmpty();
        Balance.Available sansMontant = new Balance.Available();
        Balance balance = new Balance();
        balance.setAvailable(List.of(sansMontant));
        assertThat(StripeCustomerService.nonZeroBalances(balance)).isEmpty();
    }
}
