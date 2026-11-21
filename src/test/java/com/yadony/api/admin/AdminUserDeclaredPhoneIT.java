package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.DeclaredPhoneHasher;
import com.yadony.api.auth.DeclaredPhoneLevel;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.smsotp.DeclaredPhoneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /admin/users/{id}/declared-phone/verify} de bout en bout (base H2, Firebase simulé). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("POST /admin/users/{id}/declared-phone/verify")
class AdminUserDeclaredPhoneIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired DeclaredPhoneHasher hasher;
    @Autowired DeclaredPhoneService declaredPhoneService;
    @MockitoSpyBean AuditService auditService;
    @MockitoBean FirebaseContactService firebaseContact;
    @MockitoBean PlatformSettingsService platformSettings;

    private static final UUID ADMIN_ID = UUID.randomUUID();

    private String uid;
    private String phone;
    private UserEntity user;

    @BeforeEach
    void seed() {
        uid = "verify-" + UUID.randomUUID();
        phone = "+22507" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L));
        user = user(uid);
        user.setDeclaredPhoneHash(hasher.hash(phone));
        user.setDeclaredPhoneLevel(DeclaredPhoneLevel.DECLARED);
        user = userRepository.save(user);
        when(firebaseContact.isAvailable()).thenReturn(true);
        when(firebaseContact.getContact(uid)).thenReturn(new FirebaseContactService.Contact(null, null, phone));
        when(platformSettings.smsBlockedCallingCode(anyString())).thenAnswer(inv ->
                ((String) inv.getArgument(0)).startsWith("+225") ? Optional.of("225") : Optional.empty());
    }

    @Test
    void verify_adminWithAuthority_setsYadonyVerifiedAndAudits() throws Exception {
        mockMvc.perform(post(url()).with(admin("USER_PHONE_VERIFY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declaredPhone").value(phone))
                .andExpect(jsonPath("$.declaredPhoneLevel").value("YADONY_VERIFIED"));

        UserEntity saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getDeclaredPhoneLevel()).isEqualTo(DeclaredPhoneLevel.YADONY_VERIFIED);
        assertThat(saved.getDeclaredPhoneVerifiedAt()).isNotNull();
        assertThat(saved.getDeclaredPhoneVerifiedBy()).isEqualTo(ADMIN_ID);
        verify(auditService).log(eq("USER"), eq(user.getId()), eq("ADMIN_USER_PHONE_VERIFIED"), eq(ADMIN_ID),
                argThat(payload -> !payload.toString().contains(phone)
                        && !payload.toString().contains(phone.substring(4))));
    }

    @Test
    void verify_withoutAuthority_403() throws Exception {
        mockMvc.perform(post(url()).with(admin("USER_VIEW"))).andExpect(status().isForbidden());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeclaredPhoneLevel())
                .isEqualTo(DeclaredPhoneLevel.DECLARED);
    }

    @Test
    void verify_noDeclaredPhone_409() throws Exception {
        when(firebaseContact.getContact(uid)).thenReturn(FirebaseContactService.Contact.EMPTY);

        mockMvc.perform(post(url()).with(admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("declared-phone-missing"));
    }

    @Test
    void verify_noDbHash_409() throws Exception {
        user.setDeclaredPhoneHash(null);
        user.setDeclaredPhoneLevel(null);
        userRepository.save(user);

        mockMvc.perform(post(url()).with(admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("declared-phone-missing"));
    }

    @Test
    void verify_hashMismatchWithClaim_409() throws Exception {
        when(firebaseContact.getContact(uid))
                .thenReturn(new FirebaseContactService.Contact(null, null, "+2250101010101"));

        mockMvc.perform(post(url()).with(admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("declared-phone-missing"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeclaredPhoneLevel())
                .isEqualTo(DeclaredPhoneLevel.DECLARED);
    }

    @Test
    void verify_accountHasSmsPhone_409() throws Exception {
        when(firebaseContact.getContact(uid))
                .thenReturn(new FirebaseContactService.Contact("+2250102030405", null, phone));

        mockMvc.perform(post(url()).with(admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("phone-already-set"));
    }

    @Test
    void verify_unknownUser_404() throws Exception {
        mockMvc.perform(post("/admin/users/" + UUID.randomUUID() + "/declared-phone/verify")
                        .with(admin("USER_PHONE_VERIFY")))
                .andExpect(status().isNotFound());
    }

    @Test
    void verify_idempotent_keepsOriginalVerifiedAtAndBy() throws Exception {
        mockMvc.perform(post(url()).with(admin("USER_PHONE_VERIFY"))).andExpect(status().isOk());
        UserEntity first = userRepository.findById(user.getId()).orElseThrow();
        LocalDateTime at = first.getDeclaredPhoneVerifiedAt();
        UUID otherAdmin = UUID.randomUUID();

        mockMvc.perform(post(url()).with(admin(otherAdmin, "USER_PHONE_VERIFY"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.declaredPhoneLevel").value("YADONY_VERIFIED"));

        UserEntity second = userRepository.findById(user.getId()).orElseThrow();
        assertThat(second.getDeclaredPhoneVerifiedAt()).isEqualTo(at);
        assertThat(second.getDeclaredPhoneVerifiedBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    void verify_thenUserRedeclares_backToDeclared() throws Exception {
        mockMvc.perform(post(url()).with(admin("USER_PHONE_VERIFY"))).andExpect(status().isOk());

        declaredPhoneService.declare(uid, "+2250101010101");

        UserEntity saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getDeclaredPhoneLevel()).isEqualTo(DeclaredPhoneLevel.DECLARED);
        assertThat(saved.getDeclaredPhoneVerifiedAt()).isNull();
        assertThat(saved.getDeclaredPhoneVerifiedBy()).isNull();
    }

    @Test
    void verify_doesNotWriteFirebasePhoneNumber() throws Exception {
        mockMvc.perform(post(url()).with(admin("USER_PHONE_VERIFY"))).andExpect(status().isOk());

        verify(firebaseContact, never()).updatePhone(anyString(), anyString());
        verify(firebaseContact, never()).setDeclaredPhone(anyString(), anyString());
        assertThat(firebaseContact.getContact(uid).phoneNumber()).isNull();
    }

    @Test
    void detail_showsDeclaredPhoneAndLevel_untrustedShowsDeclared() throws Exception {
        mockMvc.perform(get("/admin/users/" + user.getId()).with(admin("USER_VIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declaredPhone").value(phone))
                .andExpect(jsonPath("$.declaredPhoneLevel").value("DECLARED"));

        // Niveau stocké YADONY mais numéro changé depuis : le détail retombe à DECLARED.
        user.setDeclaredPhoneLevel(DeclaredPhoneLevel.YADONY_VERIFIED);
        userRepository.save(user);
        mockMvc.perform(get("/admin/users/" + user.getId()).with(admin("USER_VIEW")))
                .andExpect(jsonPath("$.declaredPhoneLevel").value("YADONY_VERIFIED"));
        when(firebaseContact.getContact(uid))
                .thenReturn(new FirebaseContactService.Contact(null, null, "+2250101010101"));
        mockMvc.perform(get("/admin/users/" + user.getId()).with(admin("USER_VIEW")))
                .andExpect(jsonPath("$.declaredPhoneLevel").value("DECLARED"));
    }

    @Test
    void detail_noDeclaredPhone_fieldsAbsent() throws Exception {
        when(firebaseContact.getContact(uid)).thenReturn(FirebaseContactService.Contact.EMPTY);

        mockMvc.perform(get("/admin/users/" + user.getId()).with(admin("USER_VIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declaredPhone").doesNotExist())
                .andExpect(jsonPath("$.declaredPhoneLevel").doesNotExist());
    }

    private String url() {
        return "/admin/users/" + user.getId() + "/declared-phone/verify";
    }

    private static RequestPostProcessor admin(String... permissions) {
        return admin(ADMIN_ID, permissions);
    }

    private static RequestPostProcessor admin(UUID adminId, String... permissions) {
        AdminPrincipal principal = new AdminPrincipal(adminId, "a@yadony.test", AdminRole.ADMIN, false, "uid-a");
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) authorities.add(new SimpleGrantedAuthority(p));
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private UserEntity user(String firebaseUid) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(firebaseUid);
        u.setStatus(UserStatus.ACTIVE);
        u.setRoles(new HashSet<>(List.of(Role.SENDER)));
        return userRepository.save(u);
    }
}
