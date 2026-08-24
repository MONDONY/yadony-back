package com.yadony.api.payments;

import com.stripe.param.v2.core.AccountCreateParams;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.kyc.KycVerifiedIdentityService;
import com.yadony.api.kyc.VerifiedIdentitySnapshot;
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
    @Mock KycVerifiedIdentityService verifiedIdentity;

    private ConnectAccountProvisioner provisioner;

    @BeforeEach
    void setUp() {
        StripeConnectProperties props = PaymentServiceTestFactory.defaultConnectProperties();
        provisioner = new StripeV2AccountProvisioner(
                stripeGateway, props, firebaseContact, verifiedIdentity);
        org.mockito.Mockito.lenient().when(firebaseContact.getContact(any()))
                .thenReturn(new FirebaseContactService.Contact("+33600000000", "test@yadony.app"));
        // Par defaut, pas de snapshot : chaque test du prefill pose le sien.
        org.mockito.Mockito.lenient().when(verifiedIdentity.forUser(any()))
                .thenReturn(java.util.Optional.empty());
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

    // ── Prefill depuis Stripe Identity ───────────────────────────────────────
    //
    // Le nom, et rien d'autre. Date de naissance et adresse de residence sont demandees
    // par le formulaire Connect lui-meme : il les revalide de toute facon, et les envoyer
    // d'avance n'evitait aucune saisie tout en ouvrant une classe d'echecs (une adresse
    // au pays inattendu faisait rejeter la creation entiere).

    private static final VerifiedIdentitySnapshot SNAPSHOT =
            new VerifiedIdentitySnapshot("Awa", "Diallo");

    @Test
    @DisplayName("Particulier verifie : le nom vient de Stripe Identity")
    void prefillsNameFromVerifiedIdentity() throws Exception {
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(SNAPSHOT));

        AccountCreateParams params = captureParams(buildUser(false, "FR"));

        AccountCreateParams.Identity.Individual individual = params.getIdentity().getIndividual();
        assertThat(individual).isNotNull();
        assertThat(individual.getGivenName()).isEqualTo("Awa");
        assertThat(individual.getSurname()).isEqualTo("Diallo");
    }

    @Test
    @DisplayName("Ni date de naissance ni adresse ne partent, meme connues en base : "
            + "c'est Stripe qui les demande")
    void neverSendsDateOfBirthNorAddress() throws Exception {
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(SNAPSHOT));
        UserEntity user = buildUser(false, "FR");
        // Colonnes encore alimentees pour les comptes crees avant ce changement : elles
        // ne doivent plus rien declencher.
        user.setBirthDate(java.time.LocalDate.of(1990, 4, 12));
        user.setResidenceStreet("3 avenue des Lilas");
        user.setResidenceLine2("Bat. B");
        user.setResidencePostalCode("69003");
        user.setCity("Lyon");

        AccountCreateParams.Identity.Individual individual =
                captureParams(user).getIdentity().getIndividual();

        assertThat(individual.getDateOfBirth()).isNull();
        assertThat(individual.getAddress()).isNull();
    }

    @Test
    @DisplayName("Sans snapshot ni declaratif, le compte se cree sans prefill — jamais bloque")
    void provisionsWithoutPrefillWhenNothingKnown() throws Exception {
        AccountCreateParams params = captureParams(buildUser(false, "FR"));

        assertThat(params.getIdentity().getIndividual()).isNull();
    }

    @Test
    @DisplayName("Un utilisateur sans nom nulle part ne produit aucun individual : "
            + "Stripe refuse un individual vide")
    void noIndividualWhenNameMissingEverywhere() throws Exception {
        when(verifiedIdentity.forUser(any()))
                .thenReturn(java.util.Optional.of(new VerifiedIdentitySnapshot(null, null)));
        UserEntity user = buildUser(false, "FR");
        user.setBirthDate(java.time.LocalDate.of(1990, 4, 12));

        assertThat(captureParams(user).getIdentity().getIndividual()).isNull();
    }

    @Test
    @DisplayName("Sans snapshot, le nom declare a l'inscription sert de repli")
    void fallsBackToDeclaredIdentityWhenSnapshotMissing() throws Exception {
        UserEntity user = buildUser(false, "FR");
        user.setFirstName("Awa");
        user.setLastName("Diallo");

        AccountCreateParams.Identity.Individual individual =
                captureParams(user).getIdentity().getIndividual();

        assertThat(individual).isNotNull();
        assertThat(individual.getGivenName()).isEqualTo("Awa");
        assertThat(individual.getSurname()).isEqualTo("Diallo");
    }

    @Test
    @DisplayName("L'identite verifiee prime sur le declaratif — jamais l'inverse")
    void verifiedIdentityWinsOverDeclared() throws Exception {
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(SNAPSHOT));
        UserEntity user = buildUser(false, "FR");
        // Nom declare different de celui de la piece : Stripe recoupera le verifie.
        user.setFirstName("Awé");
        user.setLastName("Autre");

        AccountCreateParams.Identity.Individual individual =
                captureParams(user).getIdentity().getIndividual();

        assertThat(individual.getGivenName()).isEqualTo("Awa");
        assertThat(individual.getSurname()).isEqualTo("Diallo");
    }

    @Test
    @DisplayName("Repli champ par champ : un nom declare comble un nom absent des outputs")
    void fallsBackFieldByField() throws Exception {
        VerifiedIdentitySnapshot surnameOnly = new VerifiedIdentitySnapshot(null, "Diallo");
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(surnameOnly));
        UserEntity user = buildUser(false, "FR");
        user.setFirstName("Awa");

        AccountCreateParams.Identity.Individual individual =
                captureParams(user).getIdentity().getIndividual();

        assertThat(individual.getGivenName()).isEqualTo("Awa");
        assertThat(individual.getSurname()).isEqualTo("Diallo");
    }

    @Test
    @DisplayName("Compte pro : aucun prefill individuel sur une entite company")
    void noIndividualPrefillForProAccounts() throws Exception {
        UserEntity pro = buildUser(true, "FR");
        pro.setFirstName("Awa");

        AccountCreateParams params = captureParams(pro);

        assertThat(params.getIdentity().getIndividual()).isNull();
        org.mockito.Mockito.verify(verifiedIdentity, never()).forUser(any());
    }
}
