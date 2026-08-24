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

    private static final VerifiedIdentitySnapshot SNAPSHOT = new VerifiedIdentitySnapshot(
            "Awa", "Diallo", 12L, 4L, 1990L,
            "8 rue du Document", null, "Paris", "75011", "FR");

    @Test
    @DisplayName("Particulier verifie : nom et date de naissance viennent de Stripe Identity")
    void prefillsNameAndDobFromVerifiedIdentity() throws Exception {
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(SNAPSHOT));

        AccountCreateParams params = captureParams(buildUser(false, "FR"));

        AccountCreateParams.Identity.Individual individual = params.getIdentity().getIndividual();
        assertThat(individual).isNotNull();
        assertThat(individual.getGivenName()).isEqualTo("Awa");
        assertThat(individual.getSurname()).isEqualTo("Diallo");
        assertThat(individual.getDateOfBirth().getDay()).isEqualTo(12L);
        assertThat(individual.getDateOfBirth().getMonth()).isEqualTo(4L);
        assertThat(individual.getDateOfBirth().getYear()).isEqualTo(1990L);
    }

    @Test
    @DisplayName("L'adresse de residence declaree prime sur celle du document")
    void residenceAddressWinsOverDocumentAddress() throws Exception {
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(SNAPSHOT));
        UserEntity user = buildUser(false, "FR");
        user.setResidenceStreet("3 avenue des Lilas");
        user.setResidenceLine2("Bat. B");
        user.setResidencePostalCode("69003");
        user.setCity("Lyon");

        AccountCreateParams.Identity.Individual.Address address =
                captureParams(user).getIdentity().getIndividual().getAddress();

        assertThat(address.getLine1()).isEqualTo("3 avenue des Lilas");
        assertThat(address.getLine2()).isEqualTo("Bat. B");
        assertThat(address.getPostalCode()).isEqualTo("69003");
        assertThat(address.getCountry()).isEqualTo("FR");
        // La ville du formulaire d'adresse vit sur users.city : elle part avec
        // la residence — jamais celle du document, qui peut etre perimee.
        assertThat(address.getCity()).isEqualTo("Lyon");
    }

    @Test
    @DisplayName("Piece etrangere en repli : aucune adresse envoyee, Stripe la reclamera")
    void foreignDocumentAddressIsNotSentAtAll() throws Exception {
        // Constate en recette : l'utilisateur avait passe l'etape adresse, et le document
        // de test Stripe porte une adresse americaine. Envoyee telle quelle sur un compte
        // FR, elle faisait echouer la creation entiere :
        //   The address country must match the identity country, which is FR.
        VerifiedIdentitySnapshot usDocument = new VerifiedIdentitySnapshot(
                "Awa", "Diallo", null, null, null,
                "1234 Main St", null, "San Francisco", "94111", "US");
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(usDocument));

        AccountCreateParams.Identity.Individual individual =
                captureParams(buildUser(false, "FR")).getIdentity().getIndividual();

        assertThat(individual).isNotNull();
        assertThat(individual.getGivenName()).isEqualTo("Awa");
        assertThat(individual.getAddress()).isNull();
    }

    @Test
    @DisplayName("Adresse du document dans le meme pays : le pays du compte fait foi")
    void sameCountryDocumentAddressUsesAccountCountry() throws Exception {
        VerifiedIdentitySnapshot frDocument = new VerifiedIdentitySnapshot(
                "Awa", "Diallo", null, null, null,
                "8 rue du Document", null, "Paris", "75011", "fr");
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(frDocument));

        AccountCreateParams.Identity.Individual.Address address =
                captureParams(buildUser(false, "FR")).getIdentity().getIndividual().getAddress();

        assertThat(address).isNotNull();
        assertThat(address.getLine1()).isEqualTo("8 rue du Document");
        // Casse normalisee sur le pays du compte, jamais celle du document.
        assertThat(address.getCountry()).isEqualTo("FR");
    }

    @Test
    @DisplayName("Residence sans ville connue : le champ ville reste vide, Stripe le demande")
    void residenceWithoutCityLeavesCityEmpty() throws Exception {
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(SNAPSHOT));
        UserEntity user = buildUser(false, "FR");
        user.setResidenceStreet("3 avenue des Lilas");

        AccountCreateParams.Identity.Individual.Address address =
                captureParams(user).getIdentity().getIndividual().getAddress();

        assertThat(address.getLine1()).isEqualTo("3 avenue des Lilas");
        assertThat(address.getCity()).isNull();
    }

    @Test
    @DisplayName("Residence passee : l'adresse du document sert de repli")
    void documentAddressUsedWhenResidenceSkipped() throws Exception {
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(SNAPSHOT));

        AccountCreateParams.Identity.Individual.Address address =
                captureParams(buildUser(false, "FR")).getIdentity().getIndividual().getAddress();

        assertThat(address.getLine1()).isEqualTo("8 rue du Document");
        assertThat(address.getCity()).isEqualTo("Paris");
        assertThat(address.getPostalCode()).isEqualTo("75011");
    }

    @Test
    @DisplayName("Sans snapshot ni declaratif, le compte se cree sans prefill — jamais bloque")
    void provisionsWithoutPrefillWhenNothingKnown() throws Exception {
        AccountCreateParams params = captureParams(buildUser(false, "FR"));

        assertThat(params.getIdentity().getIndividual()).isNull();
    }

    @Test
    @DisplayName("Sans snapshot, les infos declarees a l'inscription servent de repli")
    void fallsBackToDeclaredIdentityWhenSnapshotMissing() throws Exception {
        UserEntity user = buildUser(false, "FR");
        user.setFirstName("Awa");
        user.setLastName("Diallo");
        user.setBirthDate(java.time.LocalDate.of(1990, 4, 12));

        AccountCreateParams.Identity.Individual individual =
                captureParams(user).getIdentity().getIndividual();

        assertThat(individual).isNotNull();
        assertThat(individual.getGivenName()).isEqualTo("Awa");
        assertThat(individual.getSurname()).isEqualTo("Diallo");
        assertThat(individual.getDateOfBirth().getDay()).isEqualTo(12L);
        assertThat(individual.getDateOfBirth().getMonth()).isEqualTo(4L);
        assertThat(individual.getDateOfBirth().getYear()).isEqualTo(1990L);
    }

    @Test
    @DisplayName("L'identite verifiee prime sur le declaratif — jamais l'inverse")
    void verifiedIdentityWinsOverDeclared() throws Exception {
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(SNAPSHOT));
        UserEntity user = buildUser(false, "FR");
        // Nom declare different de celui de la piece : Stripe recoupera le verifie.
        user.setFirstName("Awé");
        user.setLastName("Autre");
        user.setBirthDate(java.time.LocalDate.of(2000, 1, 1));

        AccountCreateParams.Identity.Individual individual =
                captureParams(user).getIdentity().getIndividual();

        assertThat(individual.getGivenName()).isEqualTo("Awa");
        assertThat(individual.getSurname()).isEqualTo("Diallo");
        assertThat(individual.getDateOfBirth().getYear()).isEqualTo(1990L);
    }

    @Test
    @DisplayName("Forme reelle en production : outputs sans date de naissance (champ sensible "
            + "que Stripe ne rend pas a une cle standard) — c'est la date saisie qui part")
    void declaredDobUsedBecauseVerifiedDobIsNeverReturned() throws Exception {
        // Reproduit exactement ce que Stripe renvoie a notre cle secrete : nom et adresse
        // presents, dob absent. Sans la date saisie a l'etape « Vos informations », le
        // compte Connect partirait sans date de naissance du tout.
        VerifiedIdentitySnapshot productionShape = new VerifiedIdentitySnapshot(
                "Awa", "Diallo", null, null, null,
                "8 rue du Document", null, "Paris", "75011", "FR");
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(productionShape));
        UserEntity user = buildUser(false, "FR");
        user.setBirthDate(java.time.LocalDate.of(1990, 4, 12));

        AccountCreateParams.Identity.Individual individual =
                captureParams(user).getIdentity().getIndividual();

        assertThat(individual.getGivenName()).isEqualTo("Awa");
        assertThat(individual.getDateOfBirth()).isNotNull();
        assertThat(individual.getDateOfBirth().getDay()).isEqualTo(12L);
        assertThat(individual.getDateOfBirth().getMonth()).isEqualTo(4L);
        assertThat(individual.getDateOfBirth().getYear()).isEqualTo(1990L);
    }

    @Test
    @DisplayName("Ni date verifiee ni date saisie : le compte part sans date, jamais en erreur")
    void noDobAtAllStillProvisions() throws Exception {
        VerifiedIdentitySnapshot nameOnly = new VerifiedIdentitySnapshot(
                "Awa", "Diallo", null, null, null, null, null, null, null, null);
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(nameOnly));

        AccountCreateParams.Identity.Individual individual =
                captureParams(buildUser(false, "FR")).getIdentity().getIndividual();

        assertThat(individual).isNotNull();
        assertThat(individual.getGivenName()).isEqualTo("Awa");
        assertThat(individual.getDateOfBirth()).isNull();
    }

    @Test
    @DisplayName("Repli champ par champ : un nom declare comble un nom absent des outputs")
    void fallsBackFieldByField() throws Exception {
        VerifiedIdentitySnapshot nameless = new VerifiedIdentitySnapshot(
                null, null, 12L, 4L, 1990L, null, null, null, null, null);
        when(verifiedIdentity.forUser(any())).thenReturn(java.util.Optional.of(nameless));
        UserEntity user = buildUser(false, "FR");
        user.setFirstName("Awa");

        AccountCreateParams.Identity.Individual individual =
                captureParams(user).getIdentity().getIndividual();

        assertThat(individual.getGivenName()).isEqualTo("Awa");
        assertThat(individual.getSurname()).isNull();
        assertThat(individual.getDateOfBirth().getDay()).isEqualTo(12L);
    }

    @Test
    @DisplayName("Compte pro : aucun prefill individuel sur une entite company")
    void noIndividualPrefillForProAccounts() throws Exception {
        UserEntity pro = buildUser(true, "FR");
        pro.setFirstName("Awa");
        pro.setBirthDate(java.time.LocalDate.of(1990, 4, 12));

        AccountCreateParams params = captureParams(pro);

        assertThat(params.getIdentity().getIndividual()).isNull();
        org.mockito.Mockito.verify(verifiedIdentity, never()).forUser(any());
    }
}
