package com.yadony.api.settings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserBusinessPrefsServiceTest {

    @Mock UserBusinessPrefsRepository repository;
    @Mock UserRepository userRepository;
    @Mock CountryLockService countryLockService;
    @Mock ActiveCurrencyResolver activeCurrencyResolver;
    @InjectMocks UserBusinessPrefsService service;

    private static final String FIREBASE_UID = "uid-test";
    private static final UUID USER_ID = UUID.randomUUID();
    private final UserEntity user = new UserEntity();

    @BeforeEach
    void setUp() {
        user.setFirebaseUid(FIREBASE_UID);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        lenient().when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
        // Meme reference d'objet : muter `user` (ex. setCountry) dans le service se
        // reflete immediatement dans ce stub, comme dans un vrai contexte JPA ou la
        // meme entite reste geree par la persistence context de la transaction.
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        lenient().when(countryLockService.isLocked(USER_ID)).thenReturn(false);
        // getPrefs derive desormais la devise au lieu de rendre le cache currency_code.
        lenient().when(activeCurrencyResolver.resolve(USER_ID)).thenReturn("EUR");
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void existingUserHasCountry(String iso2) {
        user.setCountry(iso2);
    }

    // -------------------------------------------------------------------------
    // getPrefs — no row → defaults
    // -------------------------------------------------------------------------

    @Test
    void getPrefs_noRowExists_returnsDefaults() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        UserBusinessPrefsDto result = service.getPrefs(FIREBASE_UID);

        assertThat(result.weightUnit()).isEqualTo("kg");
        assertThat(result.currencyCode()).isEqualTo("EUR");
        assertThat(result.pickupRadiusKm()).isEqualTo(10);
        assertThat(result.defaultPackageWeightKg()).isEqualTo(23);
        assertThat(result.minBidPriceEur()).isEqualTo(0);
        assertThat(result.contactMode()).isNull();
        assertThat(result.responseDelayHours()).isNull();
        assertThat(result.country()).isNull();
    }

    // -------------------------------------------------------------------------
    // getPrefs — row exists → mapped values
    // -------------------------------------------------------------------------

    @Test
    void getPrefs_rowExists_returnsMappedValues() {
        UserBusinessPrefsEntity entity = buildEntity("lbs", "XOF", 20, 30, 5, "both", 6);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(activeCurrencyResolver.resolve(USER_ID)).thenReturn("XOF");

        UserBusinessPrefsDto result = service.getPrefs(FIREBASE_UID);

        assertThat(result.weightUnit()).isEqualTo("lbs");
        assertThat(result.currencyCode()).isEqualTo("XOF");
        assertThat(result.pickupRadiusKm()).isEqualTo(20);
        assertThat(result.defaultPackageWeightKg()).isEqualTo(30);
        assertThat(result.minBidPriceEur()).isEqualTo(5);
        assertThat(result.contactMode()).isEqualTo("both");
        assertThat(result.responseDelayHours()).isEqualTo(6);
    }

    @Test
    void getPrefs_reportsLockStatusAndCountryFromUser() {
        UserBusinessPrefsEntity entity = buildEntity("kg", "EUR", 10, 23, 0, null, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(countryLockService.isLocked(USER_ID)).thenReturn(true);
        existingUserHasCountry("FR");

        UserBusinessPrefsDto result = service.getPrefs(FIREBASE_UID);

        assertThat(result.currencyLocked()).isTrue();
        assertThat(result.countryLocked()).isTrue();
        assertThat(result.country()).isEqualTo("FR");
    }

    @Test
    @DisplayName("Regression : un cache currency_code perime ne s'affiche plus dans les Reglages")
    void getPrefs_staleCurrencyCache_isOverriddenByDerivedCurrency() {
        // currency_code n'est reecrit que dans upsert(). Apres V225 (pays remis a NULL),
        // la colonne d'un compte senegalais vaut encore XOF alors que la devise active
        // derivee est EUR : les Reglages affichaient XOF pendant que tout le reste de
        // l'application facturait en EUR, jusqu'au 422 a la premiere enchere XOF.
        UserBusinessPrefsEntity stale = buildEntity("kg", "XOF", 10, 23, 0, null, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(stale));
        when(activeCurrencyResolver.resolve(USER_ID)).thenReturn("EUR");

        UserBusinessPrefsDto result = service.getPrefs(FIREBASE_UID);

        assertThat(result.currencyCode()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("La devise lue est toujours celle qui sera appliquee")
    void getPrefs_derivedCurrencyMatchesTheAppliedOne() {
        UserBusinessPrefsEntity entity = buildEntity("kg", "EUR", 10, 23, 0, null, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(activeCurrencyResolver.resolve(USER_ID)).thenReturn("XOF");
        existingUserHasCountry("SN");

        assertThat(service.getPrefs(FIREBASE_UID).currencyCode()).isEqualTo("XOF");
    }

    // -------------------------------------------------------------------------
    // getPrefs — user not found → YadonyBusinessException NOT_FOUND
    // -------------------------------------------------------------------------

    @Test
    void getPrefs_userNotFound_throwsYadonyBusinessException() {
        when(userRepository.findByFirebaseUid("unknown-uid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPrefs("unknown-uid"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> {
                    YadonyBusinessException dbe = (YadonyBusinessException) ex;
                    assertThat(dbe.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(dbe.getErrorCode()).isEqualTo("user_not_found");
                });
    }

    // -------------------------------------------------------------------------
    // upsert — no existing row → creates new entity
    // -------------------------------------------------------------------------

    @Test
    void upsert_noRowExists_savesNewEntityWithAllFields() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());
        when(activeCurrencyResolver.resolve(USER_ID)).thenReturn("XAF");

        UserBusinessPrefsDto input = new UserBusinessPrefsDto(
                "lbs", "XAF", 15, 10, 3, "call", 2, null, null, null);
        UserBusinessPrefsDto result = service.upsert(FIREBASE_UID, input);

        ArgumentCaptor<UserBusinessPrefsEntity> captor = ArgumentCaptor.forClass(UserBusinessPrefsEntity.class);
        verify(repository, times(1)).save(captor.capture());

        UserBusinessPrefsEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getWeightUnit()).isEqualTo("lbs");
        // La devise sauvegardee vient du resolveur, pas du champ currencyCode de la
        // requete (ignore) : ils coincident ici uniquement parce que le stub renvoie
        // la meme valeur, pour garder les assertions simples.
        assertThat(saved.getCurrencyCode()).isEqualTo("XAF");
        assertThat(saved.getPickupRadiusKm()).isEqualTo(15);
        assertThat(saved.getDefaultPackageWeightKg()).isEqualTo(10);
        assertThat(saved.getMinBidPriceEur()).isEqualTo(3);
        assertThat(saved.getContactMode()).isEqualTo("call");
        assertThat(saved.getResponseDelayHours()).isEqualTo(2);

        // returned DTO mirrors the saved entity
        assertThat(result.weightUnit()).isEqualTo("lbs");
        assertThat(result.currencyCode()).isEqualTo("XAF");
        assertThat(result.pickupRadiusKm()).isEqualTo(15);
        assertThat(result.defaultPackageWeightKg()).isEqualTo(10);
        assertThat(result.minBidPriceEur()).isEqualTo(3);
        assertThat(result.contactMode()).isEqualTo("call");
        assertThat(result.responseDelayHours()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // upsert — existing row → updates fields in place
    // -------------------------------------------------------------------------

    @Test
    void upsert_rowExists_updatesExistingEntityFields() {
        UserBusinessPrefsEntity existing = buildEntity("kg", "EUR", 10, 23, 0, null, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(activeCurrencyResolver.resolve(USER_ID)).thenReturn("XOF");

        UserBusinessPrefsDto input = new UserBusinessPrefsDto(
                "lbs", "XOF", 25, 5, 10, "message", 4, null, null, null);
        UserBusinessPrefsDto result = service.upsert(FIREBASE_UID, input);

        ArgumentCaptor<UserBusinessPrefsEntity> captor = ArgumentCaptor.forClass(UserBusinessPrefsEntity.class);
        verify(repository, times(1)).save(captor.capture());

        UserBusinessPrefsEntity saved = captor.getValue();
        // same entity instance — userId unchanged
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getWeightUnit()).isEqualTo("lbs");
        assertThat(saved.getCurrencyCode()).isEqualTo("XOF");
        assertThat(saved.getPickupRadiusKm()).isEqualTo(25);
        assertThat(saved.getDefaultPackageWeightKg()).isEqualTo(5);
        assertThat(saved.getMinBidPriceEur()).isEqualTo(10);
        assertThat(saved.getContactMode()).isEqualTo("message");
        assertThat(saved.getResponseDelayHours()).isEqualTo(4);

        assertThat(result.weightUnit()).isEqualTo("lbs");
        assertThat(result.responseDelayHours()).isEqualTo(4);
    }

    // -------------------------------------------------------------------------
    // upsert — un client qui renvoie l'ancienne devise n'a plus aucun effet
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Un client qui renvoie l'ancienne devise dans son PUT ne provoque pas d'erreur, elle est ignoree")
    void upsert_staleCurrencyCodeFromClient_isIgnored() {
        existingUserHasCountry("FR");
        UserBusinessPrefsEntity existing = buildEntity("kg", "XOF", 10, 23, 0, null, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(activeCurrencyResolver.resolve(USER_ID)).thenReturn("EUR");

        // Le client renvoie une devise perimee ("XOF") sans changer de pays : elle
        // est purement et simplement ignoree, la devise reste celle derivee du pays.
        UserBusinessPrefsDto input = new UserBusinessPrefsDto(
                "kg", "XOF", 10, 23, 0, null, null, null, null, null);
        UserBusinessPrefsDto result = service.upsert(FIREBASE_UID, input);

        assertThat(result.currencyCode()).isEqualTo("EUR");
    }

    // -------------------------------------------------------------------------
    // upsert — pays : derivation, verrou, catalogue
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Choisir un pays ecrit le pays et derive la devise")
    void savingCountryDerivesCurrency() {
        when(activeCurrencyResolver.resolve(USER_ID)).thenReturn("CAD");

        UserBusinessPrefsDto dto = UserBusinessPrefsDto.defaults().withCountry("CA");

        UserBusinessPrefsDto saved = service.upsert(FIREBASE_UID, dto);

        assertThat(saved.country()).isEqualTo("CA");
        assertThat(saved.currencyCode()).isEqualTo("CAD");
        verify(userRepository, times(1)).save(user);
        assertThat(user.getCountry()).isEqualTo("CA");
    }

    @Test
    @DisplayName("Changer de pays est refuse une fois verrouille")
    void rejectsCountryChangeWhenLocked() {
        when(countryLockService.isLocked(USER_ID)).thenReturn(true);
        existingUserHasCountry("FR");

        assertThatThrownBy(() -> service.upsert(FIREBASE_UID,
                    UserBusinessPrefsDto.defaults().withCountry("CA")))
                .isInstanceOf(YadonyBusinessException.class)
                // NB : YadonyBusinessException#getMessage() renvoie le `detail` (texte
                // francais affiche a l'utilisateur), pas le code d'erreur — on verifie
                // donc le code via getErrorCode() plutot que hasMessageContaining.
                .satisfies(ex -> {
                    YadonyBusinessException dbe = (YadonyBusinessException) ex;
                    assertThat(dbe.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(dbe.getErrorCode()).isEqualTo("country-locked");
                });
        verify(repository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Un pays hors catalogue est refuse")
    void rejectsUnsupportedCountry() {
        assertThatThrownBy(() -> service.upsert(FIREBASE_UID,
                    UserBusinessPrefsDto.defaults().withCountry("ZZ")))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> {
                    YadonyBusinessException dbe = (YadonyBusinessException) ex;
                    assertThat(dbe.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(dbe.getErrorCode()).isEqualTo("country-unsupported");
                });
        verify(repository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Un premier choix de pays passe : un compte reellement neuf n'est jamais verrouille")
    void firstCountryChoiceOnBrandNewAccount_isAllowed() {
        // user.getCountry() est null ET rien n'a jamais engage d'argent : isLocked()
        // rend false par construction, le choix passe sans exemption dediee.
        when(countryLockService.isLocked(USER_ID)).thenReturn(false);
        when(activeCurrencyResolver.resolve(USER_ID)).thenReturn("XOF");

        UserBusinessPrefsDto result = service.upsert(FIREBASE_UID,
                UserBusinessPrefsDto.defaults().withCountry("SN"));

        assertThat(result.country()).isEqualTo("SN");
        assertThat(result.currencyCode()).isEqualTo("XOF");
    }

    @Test
    @DisplayName("Regression : un compte existant remis a country=NULL par V225 reste verrouille")
    void legacyAccountWithNullCountryAndStripeAccount_isStillLocked() {
        // Scenario exact du deploiement de V225 : le pays a ete efface en base, mais le
        // compte porte deja un compte Stripe Connect (dont le pays est immuable chez
        // Stripe). Un `country == null` ne doit surtout pas valoir « premier choix ».
        user.setCountry(null);
        user.setStripeAccountId("acct_legacy_fr");
        when(countryLockService.isLocked(USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.upsert(FIREBASE_UID,
                    UserBusinessPrefsDto.defaults().withCountry("SN")))
                .isInstanceOf(YadonyBusinessException.class)
                // getMessage() renvoie le `detail` francais, pas le code : assert sur getErrorCode().
                .satisfies(ex -> {
                    YadonyBusinessException dbe = (YadonyBusinessException) ex;
                    assertThat(dbe.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(dbe.getErrorCode()).isEqualTo("country-locked");
                });
        verify(userRepository, never()).save(any());
        verify(repository, never()).save(any());
        assertThat(user.getCountry()).isNull();
    }

    @Test
    @DisplayName("Changer de pays est permis quand ce n'est pas verrouille")
    void upsert_countryChanges_notLocked_isAllowed() {
        existingUserHasCountry("FR");
        when(countryLockService.isLocked(USER_ID)).thenReturn(false);
        when(activeCurrencyResolver.resolve(USER_ID)).thenReturn("CAD");

        UserBusinessPrefsDto result = service.upsert(FIREBASE_UID,
                UserBusinessPrefsDto.defaults().withCountry("CA"));

        assertThat(result.country()).isEqualTo("CA");
        assertThat(user.getCountry()).isEqualTo("CA");
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("Ne pas envoyer de pays laisse le pays existant inchange")
    void upsert_countryOmitted_leavesExistingCountryUntouched() {
        existingUserHasCountry("FR");
        UserBusinessPrefsEntity existing = buildEntity("kg", "EUR", 10, 23, 0, null, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(activeCurrencyResolver.resolve(USER_ID)).thenReturn("EUR");

        UserBusinessPrefsDto input = UserBusinessPrefsDto.defaults(); // country() == null

        UserBusinessPrefsDto result = service.upsert(FIREBASE_UID, input);

        assertThat(result.country()).isEqualTo("FR");
        verify(userRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UserBusinessPrefsEntity buildEntity(String weightUnit, String currencyCode,
                                                int pickupRadius, int defaultWeight,
                                                int minBid, String contactMode,
                                                Integer responseDelay) {
        UserBusinessPrefsEntity e = new UserBusinessPrefsEntity();
        e.setUserId(USER_ID);
        e.setWeightUnit(weightUnit);
        e.setCurrencyCode(currencyCode);
        e.setPickupRadiusKm(pickupRadius);
        e.setDefaultPackageWeightKg(defaultWeight);
        e.setMinBidPriceEur(minBid);
        e.setContactMode(contactMode);
        e.setResponseDelayHours(responseDelay);
        return e;
    }
}
