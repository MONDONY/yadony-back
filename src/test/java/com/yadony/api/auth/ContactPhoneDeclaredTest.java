package com.yadony.api.auth;

import com.yadony.api.auth.dto.ProfilePublicResponse;
import com.yadony.api.auth.dto.UserResponse;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.ratings.RatingService;
import com.yadony.api.ratings.dto.UserRatingsSummaryResponse;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exposition du numéro déclaré sans SMS et de son badge (tâche 4). */
@ExtendWith(MockitoExtension.class)
@DisplayName("Numéro déclaré : exposition et fiabilité du niveau")
class ContactPhoneDeclaredTest {

    private static final String DECLARED = "+245955123456";
    private static final String SMS = "+221701234567";

    private final DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("cle-de-test");
    private final DeclaredPhoneHasher unconfigured = new DeclaredPhoneHasher("");

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private FirebaseContactService firebaseContact;
    @Spy private DeclaredPhoneResolver resolver = new DeclaredPhoneResolver(
            new DeclaredPhoneHasher("cle-de-test"));
    @InjectMocks private BidService bidService;

    @AfterEach
    void clear() { TestMessages.clearRequest(); }

    private UserEntity user(String uid, DeclaredPhoneLevel level, String hashedNumber) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirebaseUid(uid);
        u.setDeclaredPhoneLevel(level);
        if (hashedNumber != null) u.setDeclaredPhoneHash(hasher.hash(hashedNumber));
        return u;
    }

    private UserEntity user(String uid) { return user(uid, null, null); }

    // ── Résolveur : règle de fiabilité ───────────────────────────────────────

    @Test
    @DisplayName("hash concordant : le niveau stocké est retenu")
    void resolver_matchingHash_keepsStoredLevel() {
        var u = user("u", DeclaredPhoneLevel.YADONY_VERIFIED, DECLARED);
        var r = new DeclaredPhoneResolver(hasher)
                .resolve(u, new FirebaseContactService.Contact(null, null, DECLARED));
        assertThat(r.number()).isEqualTo(DECLARED);
        assertThat(r.level()).isEqualTo(DeclaredPhoneLevel.YADONY_VERIFIED);
    }

    @Test
    @DisplayName("hash différent : retombe à DECLARED")
    void resolver_hashMismatch_downgradesToDeclared() {
        var u = user("u", DeclaredPhoneLevel.YADONY_VERIFIED, "+245955000000");
        var r = new DeclaredPhoneResolver(hasher)
                .resolve(u, new FirebaseContactService.Contact(null, null, DECLARED));
        assertThat(r.level()).isEqualTo(DeclaredPhoneLevel.DECLARED);
        assertThat(r.number()).isEqualTo(DECLARED);
    }

    @Test
    @DisplayName("hash nul : retombe à DECLARED")
    void resolver_nullHash_downgradesToDeclared() {
        var u = user("u", DeclaredPhoneLevel.YADONY_VERIFIED, null);
        var r = new DeclaredPhoneResolver(hasher)
                .resolve(u, new FirebaseContactService.Contact(null, null, DECLARED));
        assertThat(r.level()).isEqualTo(DeclaredPhoneLevel.DECLARED);
    }

    @Test
    @DisplayName("hasher non configuré : retombe à DECLARED")
    void resolver_hasherNotConfigured_downgradesToDeclared() {
        var u = user("u", DeclaredPhoneLevel.YADONY_VERIFIED, DECLARED);
        var r = new DeclaredPhoneResolver(unconfigured)
                .resolve(u, new FirebaseContactService.Contact(null, null, DECLARED));
        assertThat(r.level()).isEqualTo(DeclaredPhoneLevel.DECLARED);
    }

    @Test
    @DisplayName("niveau stocké absent avec un claim : DECLARED")
    void resolver_nullLevel_isDeclared() {
        var r = new DeclaredPhoneResolver(hasher).resolve(user("u"),
                new FirebaseContactService.Contact(null, null, DECLARED));
        assertThat(r.level()).isEqualTo(DeclaredPhoneLevel.DECLARED);
    }

    @Test
    @DisplayName("aucun claim : rien, même avec un niveau en base")
    void resolver_noClaim_absent() {
        var u = user("u", DeclaredPhoneLevel.YADONY_VERIFIED, DECLARED);
        var r = new DeclaredPhoneResolver(hasher)
                .resolve(u, new FirebaseContactService.Contact(null, null, null));
        assertThat(r.number()).isNull();
        assertThat(r.level()).isNull();
    }

    @Test
    @DisplayName("un numéro vérifié par SMS écarte le déclaré")
    void resolver_smsPresent_ignoresDeclared() {
        var u = user("u", DeclaredPhoneLevel.YADONY_VERIFIED, DECLARED);
        var r = new DeclaredPhoneResolver(hasher)
                .resolve(u, new FirebaseContactService.Contact(SMS, null, DECLARED));
        assertThat(r.number()).isNull();
        assertThat(r.level()).isNull();
    }

    // ── Révélation du contact ────────────────────────────────────────────────

    private String reveal(UserEntity traveler, FirebaseContactService.Contact contact, boolean hide) {
        return revealResponse(traveler, contact, hide).phoneNumber();
    }

    private com.yadony.api.matching.dto.ContactPhoneResponse revealResponse(
            UserEntity traveler, FirebaseContactService.Contact contact, boolean hide) {
        UserEntity sender = user("uid-sender");
        traveler.setHidePhoneNumber(hide);
        AnnouncementEntity ann = new AnnouncementEntity();
        ReflectionTestUtils.setField(ann, "id", UUID.randomUUID());
        ann.setTravelerId(traveler.getId());
        ann.setStatus(AnnouncementStatus.ACTIVE);
        ann.setDepartureCity("Paris");
        ann.setArrivalCity("Bissau");
        ann.setAvailableKg(new BigDecimal("10"));
        ann.setTotalKg(new BigDecimal("10"));
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setSenderId(sender.getId());
        bid.setAnnouncementId(ann.getId());
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setWeightKg(new BigDecimal("2"));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ann.getId())).thenReturn(Optional.of(ann));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        lenient().when(firebaseContact.getContact(traveler.getFirebaseUid())).thenReturn(contact);
        return bidService.getCounterpartyPhone(bid.getId(), "uid-sender");
    }

    @Test
    @DisplayName("sans numéro SMS, le numéro déclaré sort avec verification NONE")
    void contactPhone_fallsBackToDeclared_withVerificationNone() {
        var traveler = user("uid-t", DeclaredPhoneLevel.DECLARED, DECLARED);
        var r = revealResponse(traveler, new FirebaseContactService.Contact(null, null, DECLARED), false);
        assertThat(r.phoneNumber()).isEqualTo(DECLARED);
        assertThat(r.verification()).isEqualTo("NONE");
        verify(auditService).log(eq("BID"), any(), eq("CONTACT_PHONE_REVEALED"), any(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                        m -> !m.toString().contains(DECLARED)));
    }

    @Test
    @DisplayName("déclaré vérifié par Yadony : verification YADONY")
    void contactPhone_yadonyVerified_returnsYadony() {
        var traveler = user("uid-t", DeclaredPhoneLevel.YADONY_VERIFIED, DECLARED);
        var r = revealResponse(traveler, new FirebaseContactService.Contact(null, null, DECLARED), false);
        assertThat(r.phoneNumber()).isEqualTo(DECLARED);
        assertThat(r.verification()).isEqualTo("YADONY");
    }

    @Test
    @DisplayName("le numéro vérifié par SMS l'emporte sur le déclaré")
    void contactPhone_smsWins_overDeclared() {
        var traveler = user("uid-t", DeclaredPhoneLevel.YADONY_VERIFIED, DECLARED);
        var r = revealResponse(traveler, new FirebaseContactService.Contact(SMS, null, DECLARED), false);
        assertThat(r.phoneNumber()).isEqualTo(SMS);
        assertThat(r.verification()).isEqualTo("SMS");
    }

    @Test
    @DisplayName("niveau non fiable (hash différent) : NONE et non YADONY")
    void contactPhone_untrustedLevel_isNone() {
        var traveler = user("uid-t", DeclaredPhoneLevel.YADONY_VERIFIED, "+245955000000");
        var r = revealResponse(traveler, new FirebaseContactService.Contact(null, null, DECLARED), false);
        assertThat(r.verification()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("aucun numéro du tout : phoneNumber nul, verification NONE")
    void contactPhone_noNumber_none() {
        var r = revealResponse(user("uid-t"), new FirebaseContactService.Contact(null, null, null), false);
        assertThat(r.phoneNumber()).isNull();
        assertThat(r.verification()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("contrepartie qui masque son numéro : 403 inchangé")
    void contactPhone_hiddenByUser_stillForbidden() {
        var traveler = user("uid-t", DeclaredPhoneLevel.DECLARED, DECLARED);
        assertThatThrownBy(() -> reveal(traveler,
                new FirebaseContactService.Contact(null, null, DECLARED), true))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("phone-hidden-by-user");
                });
    }

    // ── Profil public ────────────────────────────────────────────────────────

    private ProfilePublicResponse publicProfile(UserEntity target, FirebaseContactService.Contact contact) {
        RatingService ratingService = mock(RatingService.class);
        UserBusinessPrefsRepository prefs = mock(UserBusinessPrefsRepository.class);
        StorageService storage = mock(StorageService.class);
        BlockVisibility blocks = mock(BlockVisibility.class);
        when(ratingService.getUserRatings(any(), anyInt(), anyInt(), any()))
                .thenReturn(new UserRatingsSummaryResponse(BigDecimal.ZERO, 0, Map.of(), List.of(), 0, 1));
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(firebaseContact.getContact(anyString())).thenReturn(contact);
        target.setKycStatus(KycStatus.VERIFIED);
        var service = new ProfilePublicService(userRepository, ratingService, prefs, storage, blocks,
                TestMessages.resolver(), firebaseContact, new DeclaredPhoneResolver(hasher));
        return service.getProfilePublic(target.getId(), UUID.randomUUID());
    }

    @Test
    @DisplayName("un numéro déclaré ne rend jamais phoneVerified vrai")
    void publicProfile_phoneVerifiedStaysFalse_forDeclared() {
        var t = user("uid-t", DeclaredPhoneLevel.YADONY_VERIFIED, DECLARED);
        var r = publicProfile(t, new FirebaseContactService.Contact(null, null, DECLARED));
        assertThat(r.phoneVerified()).isFalse();
        var d = user("uid-d", DeclaredPhoneLevel.DECLARED, DECLARED);
        assertThat(publicProfile(d, new FirebaseContactService.Contact(null, null, DECLARED))
                .phoneVerified()).isFalse();
    }

    @Test
    @DisplayName("phoneYadonyVerified vrai seulement pour un niveau YADONY fiable, sans SMS")
    void publicProfile_yadonyFlag_trueOnlyForYadonyVerified() {
        var yad = user("a", DeclaredPhoneLevel.YADONY_VERIFIED, DECLARED);
        assertThat(publicProfile(yad, new FirebaseContactService.Contact(null, null, DECLARED))
                .phoneYadonyVerified()).isTrue();
        var decl = user("b", DeclaredPhoneLevel.DECLARED, DECLARED);
        assertThat(publicProfile(decl, new FirebaseContactService.Contact(null, null, DECLARED))
                .phoneYadonyVerified()).isFalse();
        var forged = user("c", DeclaredPhoneLevel.YADONY_VERIFIED, "+245955000000");
        assertThat(publicProfile(forged, new FirebaseContactService.Contact(null, null, DECLARED))
                .phoneYadonyVerified()).isFalse();
        var sms = user("d", DeclaredPhoneLevel.YADONY_VERIFIED, DECLARED);
        var rs = publicProfile(sms, new FirebaseContactService.Contact(SMS, null, DECLARED));
        assertThat(rs.phoneVerified()).isTrue();
        assertThat(rs.phoneYadonyVerified()).isFalse();
    }

    // ── Réponse propriétaire ─────────────────────────────────────────────────

    private UserResponse ownerResponse(UserEntity u, FirebaseContactService.Contact contact) {
        var adminAuth = mock(com.yadony.api.admin.account.AdminAuthService.class);
        var storage = mock(StorageService.class);
        when(firebaseContact.getContact(anyString())).thenReturn(contact);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        var auth = new AuthService(userRepository, auditService, null, null, null, null, storage,
                adminAuth, firebaseContact, null, TestMessages.resolver(),
                new DeclaredPhoneResolver(hasher));
        return auth.toResponse(u);
    }

    @Test
    @DisplayName("le propriétaire reçoit son numéro déclaré et son niveau")
    void userResponse_ownerGetsDeclaredFields() {
        var u = user("uid-o", DeclaredPhoneLevel.YADONY_VERIFIED, DECLARED);
        var r = ownerResponse(u, new FirebaseContactService.Contact(null, null, DECLARED));
        assertThat(r.declaredPhone()).isEqualTo(DECLARED);
        assertThat(r.declaredPhoneLevel()).isEqualTo("YADONY_VERIFIED");
        assertThat(r.phoneNumber()).isNull();
    }

    @Test
    @DisplayName("niveau non fiable côté propriétaire : DECLARED")
    void userResponse_untrustedLevel_isDeclared() {
        var u = user("uid-o", DeclaredPhoneLevel.YADONY_VERIFIED, "+245955000000");
        var r = ownerResponse(u, new FirebaseContactService.Contact(null, null, DECLARED));
        assertThat(r.declaredPhoneLevel()).isEqualTo("DECLARED");
    }

    @Test
    @DisplayName("numéro SMS présent : champs déclarés nuls")
    void userResponse_smsPresent_declaredFieldsNull() {
        var u = user("uid-o", DeclaredPhoneLevel.DECLARED, DECLARED);
        var r = ownerResponse(u, new FirebaseContactService.Contact(SMS, null, DECLARED));
        assertThat(r.phoneNumber()).isEqualTo(SMS);
        assertThat(r.declaredPhone()).isNull();
        assertThat(r.declaredPhoneLevel()).isNull();
    }

    @Test
    @DisplayName("aucun claim : champs déclarés nuls")
    void userResponse_noClaim_null() {
        var r = ownerResponse(user("uid-o"), new FirebaseContactService.Contact(null, null, null));
        assertThat(r.declaredPhone()).isNull();
        assertThat(r.declaredPhoneLevel()).isNull();
    }
}
