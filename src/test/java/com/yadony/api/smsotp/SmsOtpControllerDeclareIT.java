package com.yadony.api.smsotp;

import com.yadony.api.auth.DeclaredPhoneHasher;
import com.yadony.api.auth.DeclaredPhoneLevel;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /auth/sms-otp/declare} de bout en bout (vraie base H2, Firebase simulé). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("POST /auth/sms-otp/declare")
class SmsOtpControllerDeclareIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired DeclaredPhoneHasher hasher;
    @MockitoSpyBean AuditService auditService;
    @MockitoBean FirebaseContactService firebaseContact;
    @MockitoBean PlatformSettingsService platformSettings;

    private String uid;
    private UserEntity user;
    private String phone;

    @BeforeEach
    void seed() {
        uid = "declare-" + UUID.randomUUID();
        user = user(uid);
        phone = "+22507" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L));
        when(firebaseContact.isAvailable()).thenReturn(true);
        when(firebaseContact.getContact(anyString())).thenReturn(FirebaseContactService.Contact.EMPTY);
        // Côte d'Ivoire bloquée, le reste non.
        when(platformSettings.smsBlockedCallingCode(anyString())).thenAnswer(inv ->
                ((String) inv.getArgument(0)).startsWith("+225") ? Optional.of("225") : Optional.empty());
    }

    @Test
    void declare_blockedCountry_savesHashClaimAndLevelDeclared() throws Exception {
        mockMvc.perform(declare(as(uid), phone)).andExpect(status().isOk());

        UserEntity saved = userRepository.findByFirebaseUid(uid).orElseThrow();
        assertThat(saved.getDeclaredPhoneHash()).isEqualTo(hasher.hash(phone));
        assertThat(saved.getDeclaredPhoneLevel()).isEqualTo(DeclaredPhoneLevel.DECLARED);
        verify(firebaseContact).setDeclaredPhone(uid, phone);
        verify(firebaseContact, never()).updatePhone(anyString(), anyString());
    }

    @Test
    void declare_countryNotBlocked_409() throws Exception {
        mockMvc.perform(declare(as(uid), "+33612345678"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("phone-declare-not-allowed"));
        verify(firebaseContact, never()).setDeclaredPhone(anyString(), anyString());
    }

    @Test
    void declare_accountHasVerifiedPhone_409() throws Exception {
        when(firebaseContact.getContact(uid))
                .thenReturn(new FirebaseContactService.Contact("+2250102030405", null, null));

        mockMvc.perform(declare(as(uid), phone))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("phone-already-set"));
    }

    @Test
    void declare_invalidE164_422() throws Exception {
        // Même convention que les autres DTO : la validation répond 422 (GlobalExceptionHandler).
        mockMvc.perform(declare(as(uid), "0707070707")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void declare_twice_replacesNumber_andResetsLevelToDeclared() throws Exception {
        mockMvc.perform(declare(as(uid), phone)).andExpect(status().isOk());
        UserEntity validated = userRepository.findByFirebaseUid(uid).orElseThrow();
        validated.setDeclaredPhoneLevel(DeclaredPhoneLevel.YADONY_VERIFIED);
        validated.setDeclaredPhoneVerifiedAt(java.time.LocalDateTime.now());
        validated.setDeclaredPhoneVerifiedBy(UUID.randomUUID());
        userRepository.save(validated);
        String other = "+2250101010101";

        mockMvc.perform(declare(as(uid), other)).andExpect(status().isOk());

        UserEntity saved = userRepository.findByFirebaseUid(uid).orElseThrow();
        assertThat(saved.getDeclaredPhoneHash()).isEqualTo(hasher.hash(other));
        assertThat(saved.getDeclaredPhoneLevel()).isEqualTo(DeclaredPhoneLevel.DECLARED);
        assertThat(saved.getDeclaredPhoneVerifiedAt()).isNull();
        assertThat(saved.getDeclaredPhoneVerifiedBy()).isNull();
    }

    @Test
    void declare_sameNumberOnTwoAccounts_bothAccepted() throws Exception {
        String uid2 = "declare-" + UUID.randomUUID();
        user(uid2);

        mockMvc.perform(declare(as(uid), phone)).andExpect(status().isOk());
        mockMvc.perform(declare(as(uid2), phone)).andExpect(status().isOk());

        assertThat(userRepository.findByFirebaseUid(uid2).orElseThrow().getDeclaredPhoneHash())
                .isEqualTo(userRepository.findByFirebaseUid(uid).orElseThrow().getDeclaredPhoneHash());
    }

    @Test
    void declare_guest_403() throws Exception {
        RequestPostProcessor guest = authentication(new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));

        mockMvc.perform(declare(guest, phone)).andExpect(status().isForbidden());
        verify(firebaseContact, never()).setDeclaredPhone(anyString(), anyString());
    }

    @Test
    void declare_withoutToken_401() throws Exception {
        mockMvc.perform(post("/auth/sms-otp/declare").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phone + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void declare_auditContainsNoNumber() throws Exception {
        mockMvc.perform(declare(as(uid), phone)).andExpect(status().isOk());

        verify(auditService).log(eq("USER"), eq(user.getId()), eq("USER_PHONE_DECLARED"), eq(user.getId()),
                argThat(payload -> !payload.toString().contains(phone)
                        && !payload.toString().contains(phone.substring(4))));
    }

    @Test
    void declare_firebaseUnavailable_503() throws Exception {
        when(firebaseContact.isAvailable()).thenReturn(false);

        mockMvc.perform(declare(as(uid), phone))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("declared-phone-unavailable"));
    }

    private MockHttpServletRequestBuilder declare(RequestPostProcessor auth, String number) {
        return post("/auth/sms-otp/declare").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + number + "\"}");
    }

    private static RequestPostProcessor as(String uid) {
        return authentication(new UsernamePasswordAuthenticationToken(uid, null, List.of()));
    }

    private UserEntity user(String firebaseUid) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(firebaseUid);
        u.setStatus(UserStatus.ACTIVE);
        u.setRoles(new HashSet<>(List.of(Role.SENDER)));
        return userRepository.save(u);
    }
}
