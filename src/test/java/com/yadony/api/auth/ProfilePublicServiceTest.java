package com.yadony.api.auth;

import com.yadony.api.auth.dto.ProfilePublicResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.ratings.RatingService;
import com.yadony.api.ratings.dto.RatingItemResponse;
import com.yadony.api.ratings.dto.UserRatingsSummaryResponse;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfilePublicService — tests unitaires")
class ProfilePublicServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RatingService ratingService;
    @Mock private UserBusinessPrefsRepository userBusinessPrefsRepository;
    @Mock private StorageService storageService;

    @InjectMocks private ProfilePublicService profilePublicService;

    private static final UUID USER_ID = UUID.randomUUID();

    private UserEntity user;

    @BeforeEach
    void setUp() throws Exception {
        user = new UserEntity();
        setId(user, USER_ID);
        setField(user, "username", "user1785153600");
        setField(user, "firstName", "Moussa");
        setField(user, "lastName", "Diallo");
        setField(user, "kycStatus", KycStatus.VERIFIED);
        setField(user, "isProAccount", false);
        setField(user, "kiloPro", false);
        setField(user, "totalTrips", 7);
        setField(user, "totalShipments", 5);
        setField(user, "createdAt", LocalDateTime.of(2025, 3, 15, 10, 0));
        // Default: no business prefs row → contactMode and responseDelayHours are null
        // lenient: the "user not found" test never reaches this call
        org.mockito.Mockito.lenient()
                .when(userBusinessPrefsRepository.findById(USER_ID))
                .thenReturn(Optional.empty());
        // Pass-through: return the stored value as-is so assertions on avatarUrl still work
        org.mockito.Mockito.lenient()
                .when(storageService.avatarUrl(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient()
                .when(storageService.avatarUrl(null))
                .thenReturn(null);
    }

    private UserRatingsSummaryResponse stubRatingSummary() {
        return new UserRatingsSummaryResponse(
                new BigDecimal("4.80"), 10,
                Map.of(1, 0L, 2, 0L, 3, 0L, 4, 2L, 5, 8L),
                List.of(new RatingItemResponse(5, "Top", LocalDateTime.now(), false, null, null, null, null)),
                0, 1);
    }

    @Test
    @DisplayName("utilisateur introuvable → 404 NOT_FOUND")
    void getProfilePublic_userNotFound_throws404() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profilePublicService.getProfilePublic(USER_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getErrorCode()).isEqualTo("user-not-found");
                });
    }

    @Test
    @DisplayName("utilisateur trouvé → retourne profil public complet")
    void getProfilePublic_validUser_returnsFullProfile() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());

        ProfilePublicResponse response = profilePublicService.getProfilePublic(USER_ID);

        assertThat(response.userId()).isEqualTo(USER_ID.toString());
        assertThat(response.displayName()).isEqualTo("Moussa D.");
        assertThat(response.avatarUrl()).isNull();
        assertThat(response.kycVerified()).isTrue();
        assertThat(response.completedBidsCount()).isEqualTo(12); // 7 trips + 5 shipments
        assertThat(response.averageRating()).isEqualByComparingTo(new BigDecimal("4.80"));
        assertThat(response.ratingCount()).isEqualTo(10);
        assertThat(response.memberSince()).isEqualTo("Membre depuis mars 2025");
        assertThat(response.badges()).isEmpty();
    }

    @Test
    @DisplayName("getPublicTravelerProfile → profil minimal, sans préférences de contact")
    void getPublicTravelerProfile_returnsMinimalProfile() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());

        var response = profilePublicService.getPublicTravelerProfile(USER_ID);

        assertThat(response.displayName()).isEqualTo("Moussa D.");
        assertThat(response.kycVerified()).isTrue();
        assertThat(response.completedBidsCount()).isEqualTo(12);
        assertThat(response.averageRating()).isEqualByComparingTo(new BigDecimal("4.80"));
        assertThat(response.ratingCount()).isEqualTo(10);
        assertThat(response.memberSince()).isEqualTo("Membre depuis mars 2025");

        var fields = java.util.Arrays.stream(
                com.yadony.api.auth.dto.PublicTravelerProfileResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(fields).doesNotContain("contactMode", "responseDelayHours", "phoneNumber");
    }

    @Test
    @DisplayName("getPublicTravelerProfile → 404 si utilisateur introuvable")
    void getPublicTravelerProfile_userNotFound_throws404() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profilePublicService.getPublicTravelerProfile(USER_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("kycStatus != VERIFIED → kycVerified false")
    void getProfilePublic_kycNotVerified_kycVerifiedFalse() throws Exception {
        setField(user, "kycStatus", KycStatus.PENDING);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());

        ProfilePublicResponse response = profilePublicService.getProfilePublic(USER_ID);

        assertThat(response.kycVerified()).isFalse();
    }

    @Test
    @DisplayName("displayName — prénom seul si pas de nom de famille")
    void getProfilePublic_noLastName_displayNameIsFirstNameOnly() throws Exception {
        setField(user, "lastName", null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());

        ProfilePublicResponse response = profilePublicService.getProfilePublic(USER_ID);

        assertThat(response.displayName()).isEqualTo("Moussa");
    }

    @Test
    @DisplayName("displayName — 'Utilisateur' si ni prénom ni nom")
    void getProfilePublic_noName_displayNameIsGeneric() throws Exception {
        setField(user, "firstName", null);
        setField(user, "lastName", null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());

        ProfilePublicResponse response = profilePublicService.getProfilePublic(USER_ID);

        // Le repli n'est plus « Utilisateur » mais le username du compte : deux profils sans
        // prénom restaient sinon indiscernables l'un de l'autre.
        assertThat(response.displayName()).isEqualTo("user1785153600");
    }

    @Test
    @DisplayName("response ne contient jamais phoneNumber")
    void getProfilePublic_neverContainsPhoneNumber() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());

        ProfilePublicResponse response = profilePublicService.getProfilePublic(USER_ID);

        // ProfilePublicResponse record fields must not include phone
        assertThat(response).isNotNull();
        // Verify by introspection that no field named "phone" or "phoneNumber" exists in the record
        var fields = java.util.Arrays.stream(ProfilePublicResponse.class.getRecordComponents())
                .map(rc -> rc.getName())
                .toList();
        assertThat(fields).doesNotContain("phone", "phoneNumber");
    }

    @Test
    @DisplayName("pas de prefs → contactMode et responseDelayHours sont null")
    void getProfilePublic_noPrefs_contactModeAndDelayAreNull() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());
        // userBusinessPrefsRepository already stubbed to return empty in setUp()

        ProfilePublicResponse response = profilePublicService.getProfilePublic(USER_ID);

        assertThat(response.contactMode()).isNull();
        assertThat(response.responseDelayHours()).isNull();
    }

    @Test
    @DisplayName("prefs présentes → contactMode et responseDelayHours exposés")
    void getProfilePublic_withPrefs_exposesBothFields() throws Exception {
        UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
        setField(prefs, "userId", USER_ID);
        setField(prefs, "contactMode", "WHATSAPP");
        setField(prefs, "responseDelayHours", 24);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(ratingService.getUserRatings(eq(USER_ID), eq(0), eq(3))).thenReturn(stubRatingSummary());
        when(userBusinessPrefsRepository.findById(USER_ID)).thenReturn(Optional.of(prefs));

        ProfilePublicResponse response = profilePublicService.getProfilePublic(USER_ID);

        assertThat(response.contactMode()).isEqualTo("WHATSAPP");
        assertThat(response.responseDelayHours()).isEqualTo(24);
    }

    @Test
    @DisplayName("profil public expose bio, avatar, langues et mode de transport")
    void getProfilePublic_includesBioLanguagesTransportAvatar() throws Exception {
        UserEntity u = new UserEntity();
        setId(u, USER_ID);
        setField(u, "firstName", "Moussa");
        setField(u, "lastName", "Diallo");
        setField(u, "kycStatus", KycStatus.VERIFIED);
        setField(u, "isProAccount", false);
        setField(u, "kiloPro", false);
        setField(u, "totalTrips", 0);
        setField(u, "totalShipments", 0);
        setField(u, "createdAt", LocalDateTime.of(2025, 3, 15, 10, 0));
        u.setBio("Hello");
        u.setAvatarUrl("https://cdn/a.jpg");
        u.setLanguages(Set.of("FR"));

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(ratingService.getUserRatings(USER_ID, 0, 3))
                .thenReturn(new UserRatingsSummaryResponse(
                        BigDecimal.ZERO, 0, Map.of(), List.of(), 0, 0));
        when(userBusinessPrefsRepository.findById(USER_ID)).thenReturn(Optional.empty());

        ProfilePublicResponse r = profilePublicService.getProfilePublic(USER_ID);

        assertThat(r.bio()).isEqualTo("Hello");
        assertThat(r.avatarUrl()).isEqualTo("https://cdn/a.jpg");
        assertThat(r.languages()).containsExactly("FR");
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static void setId(Object obj, UUID id) throws Exception {
        Field f = obj.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(obj, id);
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f;
        try {
            f = obj.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = obj.getClass().getSuperclass().getDeclaredField(name);
        }
        f.setAccessible(true);
        f.set(obj, value);
    }
}
