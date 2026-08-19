package com.yadony.api.settings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
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
    @Mock CurrencyLockService currencyLockService;
    @InjectMocks UserBusinessPrefsService service;

    private static final String FIREBASE_UID = "uid-test";
    private static final UUID USER_ID = UUID.randomUUID();
    private final UserEntity user = new UserEntity();

    @BeforeEach
    void setUp() {
        user.setFirebaseUid(FIREBASE_UID);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        lenient().when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
        lenient().when(currencyLockService.isLocked(USER_ID)).thenReturn(false);
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
    }

    // -------------------------------------------------------------------------
    // getPrefs — row exists → mapped values
    // -------------------------------------------------------------------------

    @Test
    void getPrefs_rowExists_returnsMappedValues() {
        UserBusinessPrefsEntity entity = buildEntity("lbs", "XOF", 20, 30, 5, "both", 6);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(entity));

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
    void getPrefs_reportsCurrencyLockedFromCurrencyLockService() {
        UserBusinessPrefsEntity entity = buildEntity("kg", "EUR", 10, 23, 0, null, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(currencyLockService.isLocked(USER_ID)).thenReturn(true);

        UserBusinessPrefsDto result = service.getPrefs(FIREBASE_UID);

        assertThat(result.currencyLocked()).isTrue();
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
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserBusinessPrefsDto input = new UserBusinessPrefsDto("lbs", "XAF", 15, 10, 3, "call", 2, null);
        UserBusinessPrefsDto result = service.upsert(FIREBASE_UID, input);

        ArgumentCaptor<UserBusinessPrefsEntity> captor = ArgumentCaptor.forClass(UserBusinessPrefsEntity.class);
        verify(repository, times(1)).save(captor.capture());

        UserBusinessPrefsEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getWeightUnit()).isEqualTo("lbs");
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
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserBusinessPrefsDto input = new UserBusinessPrefsDto("lbs", "XOF", 25, 5, 10, "message", 4, null);
        UserBusinessPrefsDto result = service.upsert(FIREBASE_UID, input);

        ArgumentCaptor<UserBusinessPrefsEntity> captor = ArgumentCaptor.forClass(UserBusinessPrefsEntity.class);
        verify(repository, times(1)).save(captor.capture());

        UserBusinessPrefsEntity saved = captor.getValue();
        // same entity instance — userId unchanged
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        // all 7 fields updated
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
    // upsert — lot 2 : gel de la devise au premier mouvement d'argent
    // -------------------------------------------------------------------------

    @Test
    void upsert_currencyUnchanged_neverGuardsButStillReportsLockStatus() {
        // Le taux de gel n'est jamais consulté pour BLOQUER une devise inchangée,
        // mais la réponse renseigne quand même currencyLocked() : c'est ce champ que
        // le front grise après coup, indépendamment de ce qui vient d'être modifié.
        UserBusinessPrefsEntity existing = buildEntity("kg", "EUR", 10, 23, 0, null, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(currencyLockService.isLocked(USER_ID)).thenReturn(true);

        UserBusinessPrefsDto input = new UserBusinessPrefsDto("kg", "EUR", 20, 23, 0, null, null, null);
        UserBusinessPrefsDto result = service.upsert(FIREBASE_UID, input);

        assertThat(result.currencyCode()).isEqualTo("EUR");
        assertThat(result.pickupRadiusKm()).isEqualTo(20);
        assertThat(result.currencyLocked()).isTrue();
        verify(currencyLockService, times(1)).isLocked(USER_ID);
    }

    @Test
    void upsert_currencyChanged_notLocked_isAllowed() {
        UserBusinessPrefsEntity existing = buildEntity("kg", "EUR", 10, 23, 0, null, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(currencyLockService.isLocked(USER_ID)).thenReturn(false);

        UserBusinessPrefsDto input = new UserBusinessPrefsDto("kg", "XAF", 10, 23, 0, null, null, null);
        UserBusinessPrefsDto result = service.upsert(FIREBASE_UID, input);

        assertThat(result.currencyCode()).isEqualTo("XAF");
    }

    @Test
    void upsert_currencyChanged_locked_throws422CurrencyLocked() {
        UserBusinessPrefsEntity existing = buildEntity("kg", "EUR", 10, 23, 0, null, null);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(currencyLockService.isLocked(USER_ID)).thenReturn(true);

        UserBusinessPrefsDto input = new UserBusinessPrefsDto("kg", "XAF", 10, 23, 0, null, null, null);

        assertThatThrownBy(() -> service.upsert(FIREBASE_UID, input))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> {
                    YadonyBusinessException dbe = (YadonyBusinessException) ex;
                    assertThat(dbe.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(dbe.getErrorCode()).isEqualTo("currency-locked");
                });
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_noExistingRow_currencyChoiceIsFree() {
        // Premier choix de devise (onboarding) : aucune ligne n'existe encore, donc
        // rien à figer — la garde 422 ne se déclenche jamais ici, même si
        // currencyLockService est quand même consulté pour peupler la réponse.
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserBusinessPrefsDto input = new UserBusinessPrefsDto("kg", "XOF", 10, 23, 0, null, null, null);
        UserBusinessPrefsDto result = service.upsert(FIREBASE_UID, input);

        assertThat(result.currencyCode()).isEqualTo("XOF");
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
