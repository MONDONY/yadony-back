package com.yadony.api.payments;

import com.stripe.param.v2.core.AccountCreateParams;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.StripeConnectProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Couvre la construction des paramètres Accounts v2. {@link PaymentService} n'orchestre
 * plus que le verrou, la relecture et la sauvegarde autour de cet appel.
 */
@ExtendWith(MockitoExtension.class)
class StripeV2AccountProvisionerTest {

    @Mock StripeGateway stripeGateway;
    @Mock FirebaseContactService firebaseContact;

    private ConnectAccountProvisioner provisioner;

    @BeforeEach
    void setUp() {
        StripeConnectProperties props = PaymentServiceTestFactory.defaultConnectProperties();
        provisioner = new StripeV2AccountProvisioner(stripeGateway, props, firebaseContact);
        org.mockito.Mockito.lenient().when(firebaseContact.getContact(any()))
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

    /** Capture les paramètres envoyés à Stripe pour une création réussie. */
    private AccountCreateParams captureParams(UserEntity user) throws Exception {
        com.stripe.model.v2.core.Account created =
                org.mockito.Mockito.mock(com.stripe.model.v2.core.Account.class);
        org.mockito.Mockito.lenient().when(created.getId()).thenReturn("acct_created");
        ArgumentCaptor<AccountCreateParams> captor =
                ArgumentCaptor.forClass(AccountCreateParams.class);
        when(stripeGateway.createAccountV2(captor.capture())).thenReturn(created);

        provisioner.provision(user);

        return captor.getValue();
    }

    // ── Gardes avant appel réseau ────────────────────────────────────────────

    @Test
    @DisplayName("Sans pays renseigne, aucun compte Connect n'est cree")
    void refusesToProvisionWithoutCountry() throws Exception {
        UserEntity user = buildUser(false, null);

        assertThatThrownBy(() -> provisioner.provision(user))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getErrorCode()).isEqualTo("country-required");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });

        verify(stripeGateway, never()).createAccountV2(any());
    }

    @Test
    @DisplayName("Pays vide (chaine vide), aucun compte Connect n'est cree")
    void refusesToProvisionWithBlankCountry() throws Exception {
        UserEntity user = buildUser(false, "");

        assertThatThrownBy(() -> provisioner.provision(user))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("country-required"));

        verify(stripeGateway, never()).createAccountV2(any());
    }

    @Test
    @DisplayName("Pays desservi par yadony mais non couvert par Stripe : refus explicite, sans appel reseau")
    void refusesCountryNotCoveredByStripe() throws Exception {
        UserEntity user = buildUser(false, "SN");

        assertThatThrownBy(() -> provisioner.provision(user))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getErrorCode()).isEqualTo("country-not-supported-by-stripe");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });

        verify(stripeGateway, never()).createAccountV2(any());
    }

    // ── Identite ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Compte particulier : entity_type individual")
    void nonPro_setsEntityTypeIndividual() throws Exception {
        AccountCreateParams params = captureParams(buildUser(false, "FR"));

        assertThat(params.getIdentity().getEntityType())
                .isEqualTo(AccountCreateParams.Identity.EntityType.INDIVIDUAL);
    }

    @Test
    @DisplayName("Compte professionnel : entity_type company")
    void pro_setsEntityTypeCompany() throws Exception {
        AccountCreateParams params = captureParams(buildUser(true, "FR"));

        assertThat(params.getIdentity().getEntityType())
                .isEqualTo(AccountCreateParams.Identity.EntityType.COMPANY);
    }

    @Test
    @DisplayName("Le pays de l'utilisateur est transmis, jamais une valeur par defaut")
    void passesUserCountry() throws Exception {
        AccountCreateParams params = captureParams(buildUser(false, "BE"));

        assertThat(params.getIdentity().getCountry()).isEqualTo("BE");
    }

    // ── Configuration ────────────────────────────────────────────────────────

    @Test
    @DisplayName("La capacite stripe_transfers est demandee")
    void requestsStripeTransfers() throws Exception {
        AccountCreateParams params = captureParams(buildUser(false, "FR"));

        assertThat(params.getConfiguration().getRecipient()
                .getCapabilities().getStripeBalance().getStripeTransfers().getRequested())
                .isTrue();
    }

    @Test
    @DisplayName("Aucune configuration merchant : elle declencherait account_token_required en France")
    void neverRequestsMerchantConfiguration() throws Exception {
        AccountCreateParams params = captureParams(buildUser(false, "FR"));

        assertThat(params.getConfiguration().getMerchant()).isNull();
        assertThat(params.getConfiguration().getCustomer()).isNull();
    }

    @Test
    @DisplayName("Le tableau de bord reproduit l'experience Express")
    void usesExpressDashboard() throws Exception {
        AccountCreateParams params = captureParams(buildUser(false, "FR"));

        assertThat(params.getDashboard()).isEqualTo(AccountCreateParams.Dashboard.EXPRESS);
    }

    @Test
    @DisplayName("Pertes et frais sont a la charge de l'application, pas de Stripe")
    void assignsResponsibilitiesToApplication() throws Exception {
        AccountCreateParams params = captureParams(buildUser(false, "FR"));

        assertThat(params.getDefaults().getResponsibilities().getLossesCollector())
                .isEqualTo(AccountCreateParams.Defaults.Responsibilities.LossesCollector.APPLICATION);
        assertThat(params.getDefaults().getResponsibilities().getFeesCollector())
                .isEqualTo(AccountCreateParams.Defaults.Responsibilities.FeesCollector.APPLICATION);
    }

    @Test
    @DisplayName("Le profil commercial porte l'URL et la description de la plateforme")
    void carriesBusinessProfile() throws Exception {
        AccountCreateParams params = captureParams(buildUser(false, "FR"));

        assertThat(params.getDefaults().getProfile().getBusinessUrl())
                .isEqualTo("https://yadony.app");
        assertThat(params.getDefaults().getProfile().getProductDescription())
                .isEqualTo("Transport de colis entre particuliers via la plateforme Yadony");
    }

    // ── Tracabilite et retour ────────────────────────────────────────────────

    @Test
    @DisplayName("Le compte porte l'identifiant yadony de l'utilisateur en metadonnee")
    void tagsAccountWithUserId() throws Exception {
        UserEntity user = buildUser(false, "FR");

        AccountCreateParams params = captureParams(user);

        assertThat(params.getMetadata()).containsEntry("user_id", user.getId().toString());
    }

    @Test
    @DisplayName("L'adresse de contact vient de Firebase")
    void usesFirebaseContactEmail() throws Exception {
        AccountCreateParams params = captureParams(buildUser(false, "FR"));

        assertThat(params.getContactEmail()).isEqualTo("test@yadony.app");
    }

    @Test
    @DisplayName("L'identifiant du compte cree est rendu a l'appelant")
    void returnsCreatedAccountId() throws Exception {
        com.stripe.model.v2.core.Account created =
                org.mockito.Mockito.mock(com.stripe.model.v2.core.Account.class);
        when(created.getId()).thenReturn("acct_returned");
        when(stripeGateway.createAccountV2(any())).thenReturn(created);

        String accountId = provisioner.provision(buildUser(false, "FR"));

        assertThat(accountId).isEqualTo("acct_returned");
    }
}
