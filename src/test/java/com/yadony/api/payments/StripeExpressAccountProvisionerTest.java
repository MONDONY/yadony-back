package com.yadony.api.payments;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.config.StripeConnectProperties;
import com.stripe.model.Account;
import com.stripe.param.AccountCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Extraction Lot 4 : mêmes assertions que l'ancien
 * {@code StripeConnectAccountCreationTest} au niveau service, mais isolées ici
 * sur le seul provisioner — {@link PaymentService} n'orchestre plus que le
 * verrou/relecture/sauvegarde autour de cet appel.
 */
@ExtendWith(MockitoExtension.class)
class StripeExpressAccountProvisionerTest {

    @Mock StripeGateway stripeGateway;
    @Mock FirebaseContactService firebaseContact;

    private ConnectAccountProvisioner provisioner;

    @BeforeEach
    void setUp() {
        StripeConnectProperties props = PaymentServiceTestFactory.defaultConnectProperties();
        provisioner = new StripeExpressAccountProvisioner(stripeGateway, props, firebaseContact);
        when(firebaseContact.getContact(any()))
                .thenReturn(new FirebaseContactService.Contact("+33600000000", "test@yadony.app"));
    }

    private UserEntity buildUser(boolean isPro, String country) {
        UserEntity u = new UserEntity();
        PaymentServiceTestFactory.setId(u, UUID.randomUUID());
        u.setFirebaseUid("uid-test");
        u.setProAccount(isPro);
        u.setCountry(country);
        return u;
    }

    @Test
    void nonPro_setsBusinessTypeIndividual() throws Exception {
        UserEntity user = buildUser(false, "FR");
        Account mockAccount = mockCreatedAccount("acct_individual");
        ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
        when(stripeGateway.createAccount(captor.capture())).thenReturn(mockAccount);

        provisioner.provision(user);

        assertThat(captor.getValue().getBusinessType())
                .isEqualTo(AccountCreateParams.BusinessType.INDIVIDUAL);
    }

    @Test
    void pro_setsBusinessTypeCompany() throws Exception {
        UserEntity user = buildUser(true, "FR");
        Account mockAccount = mockCreatedAccount("acct_company");
        ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
        when(stripeGateway.createAccount(captor.capture())).thenReturn(mockAccount);

        provisioner.provision(user);

        assertThat(captor.getValue().getBusinessType())
                .isEqualTo(AccountCreateParams.BusinessType.COMPANY);
    }

    @Test
    void hasCardPaymentsAndTransfersCapabilitiesRequested() throws Exception {
        UserEntity user = buildUser(false, "FR");
        Account mockAccount = mockCreatedAccount("acct_cap");
        ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
        when(stripeGateway.createAccount(captor.capture())).thenReturn(mockAccount);

        provisioner.provision(user);

        AccountCreateParams params = captor.getValue();
        assertThat(params.getCapabilities().getCardPayments().getRequested()).isTrue();
        assertThat(params.getCapabilities().getTransfers().getRequested()).isTrue();
    }

    @Test
    void countryComesFromUser_notHardcoded() throws Exception {
        UserEntity user = buildUser(false, "SN");
        Account mockAccount = mockCreatedAccount("acct_sn");
        ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
        when(stripeGateway.createAccount(captor.capture())).thenReturn(mockAccount);

        provisioner.provision(user);

        assertThat(captor.getValue().getCountry()).isEqualTo("SN");
    }

    @Test
    void businessProfileHasCorrectMccAndDescription() throws Exception {
        UserEntity user = buildUser(false, "FR");
        Account mockAccount = mockCreatedAccount("acct_bp");
        ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
        when(stripeGateway.createAccount(captor.capture())).thenReturn(mockAccount);

        provisioner.provision(user);

        AccountCreateParams.BusinessProfile bp = captor.getValue().getBusinessProfile();
        assertThat(bp.getMcc()).isEqualTo("4215");
        assertThat(bp.getProductDescription())
                .isEqualTo("Transport de colis entre particuliers via la plateforme Yadony");
        assertThat(bp.getUrl()).isEqualTo("https://yadony.app");
    }

    @Test
    void payoutScheduleIsDaily() throws Exception {
        UserEntity user = buildUser(false, "FR");
        Account mockAccount = mockCreatedAccount("acct_payout");
        ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
        when(stripeGateway.createAccount(captor.capture())).thenReturn(mockAccount);

        provisioner.provision(user);

        assertThat(captor.getValue().getSettings().getPayouts().getSchedule().getInterval())
                .isEqualTo(AccountCreateParams.Settings.Payouts.Schedule.Interval.DAILY);
    }

    @Test
    void returnsTheAccountCreatedByTheGateway() throws Exception {
        UserEntity user = buildUser(false, "FR");
        Account mockAccount = mockCreatedAccount("acct_returned");
        when(stripeGateway.createAccount(any())).thenReturn(mockAccount);

        Account result = provisioner.provision(user);

        assertThat(result.getId()).isEqualTo("acct_returned");
    }

    private Account mockCreatedAccount(String id) {
        Account mockAccount = org.mockito.Mockito.mock(Account.class);
        org.mockito.Mockito.lenient().when(mockAccount.getId()).thenReturn(id);
        return mockAccount;
    }
}
