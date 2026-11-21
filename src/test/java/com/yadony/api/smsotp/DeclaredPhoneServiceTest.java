package com.yadony.api.smsotp;

import com.yadony.api.auth.DeclaredPhoneHasher;
import com.yadony.api.auth.DeclaredPhoneLevel;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.FirebaseContactService.Contact;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.UserPhoneAttachedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DeclaredPhoneService")
class DeclaredPhoneServiceTest {

    private static final String UID = "uid-1";
    private static final String PHONE = "+2250707070707";
    private static final String OTHER = "+2250101010101";

    @Mock UserRepository userRepository;
    @Mock FirebaseContactService firebaseContact;
    @Mock AuditService auditService;
    @Mock SmsOtpService smsOtpService;
    @Mock SmsOtpProperties properties;

    DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("cle-de-test");
    DeclaredPhoneService service;
    UserEntity user;

    @BeforeEach
    void setUp() {
        service = new DeclaredPhoneService(userRepository, firebaseContact, auditService,
                smsOtpService, properties, hasher);
        user = new UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setFirebaseUid(UID);
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(user));
        when(firebaseContact.isAvailable()).thenReturn(true);
        when(firebaseContact.getContact(UID)).thenReturn(Contact.EMPTY);
        when(properties.isCountryAllowed(anyString())).thenReturn(true);
        when(smsOtpService.blockedCallingCode(anyString())).thenReturn(Optional.of("225"));
    }

    @Test
    void declare_blockedCountry_savesHashClaimAndLevelDeclared() {
        service.declare(UID, PHONE);

        assertThat(user.getDeclaredPhoneHash()).isEqualTo(hasher.hash(PHONE));
        assertThat(user.getDeclaredPhoneLevel()).isEqualTo(DeclaredPhoneLevel.DECLARED);
        verify(userRepository).saveAndFlush(user);
        verify(firebaseContact).setDeclaredPhone(UID, PHONE);
        verify(firebaseContact, never()).updatePhone(anyString(), anyString());
    }

    @Test
    void declare_countryNotBlocked_409() {
        when(smsOtpService.blockedCallingCode(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.declare(UID, PHONE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("phone-declare-not-allowed");
                });
        verify(firebaseContact, never()).setDeclaredPhone(anyString(), anyString());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void declare_countryNotServed_422() {
        when(properties.isCountryAllowed(PHONE)).thenReturn(false);

        assertThatThrownBy(() -> service.declare(UID, PHONE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("phone-country-not-supported");
                });
    }

    @Test
    void declare_accountHasVerifiedPhone_409() {
        when(firebaseContact.getContact(UID)).thenReturn(new Contact("+33612345678", null, null));

        assertThatThrownBy(() -> service.declare(UID, PHONE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("phone-already-set");
                });
        verify(firebaseContact, never()).setDeclaredPhone(anyString(), anyString());
    }

    @Test
    void declare_unknownUser_404() {
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.declare(UID, PHONE))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void declare_twice_replacesNumber_andResetsLevelToDeclared() {
        service.declare(UID, PHONE);
        user.setDeclaredPhoneLevel(DeclaredPhoneLevel.YADONY_VERIFIED);
        user.setDeclaredPhoneVerifiedAt(LocalDateTime.now());
        user.setDeclaredPhoneVerifiedBy(UUID.randomUUID());

        service.declare(UID, OTHER);

        assertThat(user.getDeclaredPhoneHash()).isEqualTo(hasher.hash(OTHER));
        assertThat(user.getDeclaredPhoneLevel()).isEqualTo(DeclaredPhoneLevel.DECLARED);
        assertThat(user.getDeclaredPhoneVerifiedAt()).isNull();
        assertThat(user.getDeclaredPhoneVerifiedBy()).isNull();
        verify(firebaseContact).setDeclaredPhone(UID, OTHER);
    }

    @Test
    void declare_sameNumberAgain_keepsAdminValidation() {
        service.declare(UID, PHONE);
        LocalDateTime at = LocalDateTime.now();
        UUID by = UUID.randomUUID();
        user.setDeclaredPhoneLevel(DeclaredPhoneLevel.YADONY_VERIFIED);
        user.setDeclaredPhoneVerifiedAt(at);
        user.setDeclaredPhoneVerifiedBy(by);

        service.declare(UID, PHONE);

        assertThat(user.getDeclaredPhoneLevel()).isEqualTo(DeclaredPhoneLevel.YADONY_VERIFIED);
        assertThat(user.getDeclaredPhoneVerifiedAt()).isEqualTo(at);
        assertThat(user.getDeclaredPhoneVerifiedBy()).isEqualTo(by);
    }

    @Test
    void declare_sameNumberOnTwoAccounts_bothAccepted() {
        UserEntity second = new UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(second, "id", UUID.randomUUID());
        second.setFirebaseUid("uid-2");
        when(userRepository.findByFirebaseUid("uid-2")).thenReturn(Optional.of(second));
        when(firebaseContact.getContact("uid-2")).thenReturn(Contact.EMPTY);

        service.declare(UID, PHONE);
        service.declare("uid-2", PHONE);

        assertThat(second.getDeclaredPhoneHash()).isEqualTo(user.getDeclaredPhoneHash());
        verify(firebaseContact).setDeclaredPhone("uid-2", PHONE);
    }

    @Test
    void declare_auditContainsNoNumber() {
        service.declare(UID, PHONE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(user.getId()), eq("USER_PHONE_DECLARED"),
                eq(user.getId()), payload.capture());
        assertThat(payload.getValue().toString())
                .doesNotContain(PHONE).doesNotContain(PHONE.substring(4)).doesNotContain(hasher.hash(PHONE));
        assertThat(payload.getValue()).containsEntry("level", "DECLARED");
    }

    @Test
    void declare_hasherNotConfigured_503() {
        service = new DeclaredPhoneService(userRepository, firebaseContact, auditService,
                smsOtpService, properties, new DeclaredPhoneHasher(""));

        assertThatThrownBy(() -> service.declare(UID, PHONE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("declared-phone-unavailable");
                });
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void declare_firebaseUnavailable_503() {
        when(firebaseContact.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.declare(UID, PHONE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("declared-phone-unavailable");
                });
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void phoneAttachedBySms_clearsDeclaredClaimHashAndLevel() {
        user.setDeclaredPhoneHash(hasher.hash(PHONE));
        user.setDeclaredPhoneLevel(DeclaredPhoneLevel.YADONY_VERIFIED);
        user.setDeclaredPhoneVerifiedAt(LocalDateTime.now());
        user.setDeclaredPhoneVerifiedBy(UUID.randomUUID());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.onPhoneAttached(new UserPhoneAttachedEvent(user.getId()));

        assertThat(user.getDeclaredPhoneHash()).isNull();
        assertThat(user.getDeclaredPhoneLevel()).isNull();
        assertThat(user.getDeclaredPhoneVerifiedAt()).isNull();
        assertThat(user.getDeclaredPhoneVerifiedBy()).isNull();
        verify(userRepository).save(user);
        verify(firebaseContact).setDeclaredPhone(UID, null);
    }

    @Test
    void phoneAttachedBySms_withoutDeclaredPhone_doesNothing() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.onPhoneAttached(new UserPhoneAttachedEvent(user.getId()));

        verify(firebaseContact, never()).setDeclaredPhone(anyString(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void phoneAttachedBySms_firebaseFailure_propagatesAndKeepsHash() {
        user.setDeclaredPhoneHash(hasher.hash(PHONE));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(firebaseContact).setDeclaredPhone(UID, null);

        assertThatThrownBy(() -> service.onPhoneAttached(new UserPhoneAttachedEvent(user.getId())))
                .hasMessage("boom");
        assertThat(user.getDeclaredPhoneHash()).isNotNull();
        verify(userRepository, never()).save(any());
    }

    @Test
    void phoneAttachedBySms_orphanClaimWithoutHash_isCleared() {
        when(firebaseContact.getContact(UID)).thenReturn(new Contact("+33612345678", null, PHONE));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.onPhoneAttached(new UserPhoneAttachedEvent(user.getId()));

        verify(firebaseContact).setDeclaredPhone(UID, null);
    }

    @Test
    void declare_firebaseRefuses_auditAndSaveHappenBeforeClaimWrite() {
        org.mockito.Mockito.doThrow(new RuntimeException("firebase"))
                .when(firebaseContact).setDeclaredPhone(UID, PHONE);

        assertThatThrownBy(() -> service.declare(UID, PHONE)).hasMessage("firebase");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(userRepository, auditService, firebaseContact);
        order.verify(userRepository).saveAndFlush(user);
        order.verify(auditService).log(anyString(), any(), eq("USER_PHONE_DECLARED"), any(), any());
        order.verify(firebaseContact).setDeclaredPhone(UID, PHONE);
    }
}
