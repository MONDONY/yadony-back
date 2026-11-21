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
import org.springframework.http.MediaType;
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
import static org.mockito.ArgumentMatchers.any;
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
        // Par défaut : aucun propriétaire vérifié par SMS (un mock rendrait null, traité comme une panne).
        when(firebaseContact.findPhoneOwner(anyString()))
                .thenReturn(FirebaseContactService.PhoneOwnerLookup.notFound());
        claim(new FirebaseContactService.Contact(null, null, phone));
        when(platformSettings.smsBlockedCallingCode(anyString())).thenAnswer(inv ->
                ((String) inv.getArgument(0)).startsWith("+225") ? Optional.of("225") : Optional.empty());
    }

    @Test
    void verify_adminWithAuthority_setsYadonyVerifiedAndAudits() throws Exception {
        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
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
        mockMvc.perform(verifyShowing(phone, admin("USER_VIEW"))).andExpect(status().isForbidden());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeclaredPhoneLevel())
                .isEqualTo(DeclaredPhoneLevel.DECLARED);
    }

    @Test
    void verify_noDeclaredPhone_409() throws Exception {
        claim(FirebaseContactService.Contact.EMPTY);

        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("declared-phone-missing"));
    }

    @Test
    void verify_noDbHash_409() throws Exception {
        user.setDeclaredPhoneHash(null);
        user.setDeclaredPhoneLevel(null);
        userRepository.save(user);

        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("declared-phone-missing"));
    }

    @Test
    void verify_hashMismatchWithClaim_409() throws Exception {
        claim(new FirebaseContactService.Contact(null, null, "+2250101010101"));

        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("declared-phone-missing"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeclaredPhoneLevel())
                .isEqualTo(DeclaredPhoneLevel.DECLARED);
    }

    @Test
    void verify_accountHasSmsPhone_409() throws Exception {
        claim(new FirebaseContactService.Contact("+2250102030405", null, phone));

        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("phone-already-set"));
    }

    @Test
    void verify_unknownUser_404() throws Exception {
        mockMvc.perform(post("/admin/users/" + UUID.randomUUID() + "/declared-phone/verify")
                        .with(admin("USER_PHONE_VERIFY")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"declaredPhone\":\"" + phone + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void verify_idempotent_keepsOriginalVerifiedAtAndBy() throws Exception {
        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY"))).andExpect(status().isOk());
        UserEntity first = userRepository.findById(user.getId()).orElseThrow();
        LocalDateTime at = first.getDeclaredPhoneVerifiedAt();
        UUID otherAdmin = UUID.randomUUID();

        mockMvc.perform(verifyShowing(phone, admin(otherAdmin, "USER_PHONE_VERIFY"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.declaredPhoneLevel").value("YADONY_VERIFIED"));

        UserEntity second = userRepository.findById(user.getId()).orElseThrow();
        assertThat(second.getDeclaredPhoneVerifiedAt()).isEqualTo(at);
        assertThat(second.getDeclaredPhoneVerifiedBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    void verify_thenUserRedeclares_backToDeclared() throws Exception {
        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY"))).andExpect(status().isOk());

        declaredPhoneService.declare(uid, "+2250101010101");

        UserEntity saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getDeclaredPhoneLevel()).isEqualTo(DeclaredPhoneLevel.DECLARED);
        assertThat(saved.getDeclaredPhoneVerifiedAt()).isNull();
        assertThat(saved.getDeclaredPhoneVerifiedBy()).isNull();
    }

    @Test
    void verify_doesNotWriteFirebasePhoneNumber() throws Exception {
        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY"))).andExpect(status().isOk());

        verify(firebaseContact, never()).updatePhone(anyString(), anyString());
        verify(firebaseContact, never()).setDeclaredPhone(anyString(), anyString());
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
        claim(new FirebaseContactService.Contact(null, null, "+2250101010101"));
        mockMvc.perform(get("/admin/users/" + user.getId()).with(admin("USER_VIEW")))
                .andExpect(jsonPath("$.declaredPhoneLevel").value("DECLARED"));
    }

    @Test
    void detail_noDeclaredPhone_fieldsAbsent() throws Exception {
        claim(FirebaseContactService.Contact.EMPTY);

        mockMvc.perform(get("/admin/users/" + user.getId()).with(admin("USER_VIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declaredPhone").doesNotExist())
                .andExpect(jsonPath("$.declaredPhoneLevel").doesNotExist());
    }

    @Test
    void verify_numberChangedSincePageLoad_409() throws Exception {
        // L'admin a vu et appelé « phone » ; entre-temps le compte a redéclaré un autre numéro.
        String other = "+2250101010101";
        user.setDeclaredPhoneHash(hasher.hash(other));
        user.setDeclaredPhoneLevel(DeclaredPhoneLevel.DECLARED);
        userRepository.save(user);
        claim(new FirebaseContactService.Contact(null, null, other));

        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("declared-phone-changed"));

        UserEntity saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getDeclaredPhoneLevel()).isEqualTo(DeclaredPhoneLevel.DECLARED);
        assertThat(saved.getDeclaredPhoneVerifiedBy()).isNull();
    }

    @Test
    void verify_bodyDiffersFromClaimEvenWhenHashMatchesNothing_409() throws Exception {
        mockMvc.perform(verifyShowing("+2250101010101", admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("declared-phone-changed"));
    }

    @Test
    void verify_missingBody_isRejected() throws Exception {
        mockMvc.perform(post(url()).with(admin("USER_PHONE_VERIFY"))).andExpect(status().is4xxClientError());
        mockMvc.perform(post(url()).with(admin("USER_PHONE_VERIFY")).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post(url()).with(admin("USER_PHONE_VERIFY")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"declaredPhone\":\"0707\"}")).andExpect(status().isUnprocessableEntity());
        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeclaredPhoneLevel())
                .isEqualTo(DeclaredPhoneLevel.DECLARED);
    }

    @Test
    void verify_authorityWithoutAdminRole_403() throws Exception {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "a@yadony.test", AdminRole.ADMIN, false, "uid-a");
        RequestPostProcessor noRole = authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("USER_PHONE_VERIFY"))));

        mockMvc.perform(verifyShowing(phone, noRole)).andExpect(status().isForbidden());
    }

    @Test
    void verify_numberOwnedBySmsOnAnotherAccount_409() throws Exception {
        UserEntity smsOwner = user("sms-" + UUID.randomUUID());
        when(firebaseContact.findPhoneOwner(phone))
                .thenReturn(FirebaseContactService.PhoneOwnerLookup.found(smsOwner.getFirebaseUid()));

        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("declared-phone-conflict"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeclaredPhoneLevel())
                .isEqualTo(DeclaredPhoneLevel.DECLARED);
    }

    @Test
    void verify_numberAlreadyYadonyVerifiedOnAnotherAccount_409() throws Exception {
        UserEntity other = user("other-" + UUID.randomUUID());
        other.setDeclaredPhoneHash(hasher.hash(phone));
        other.setDeclaredPhoneLevel(DeclaredPhoneLevel.YADONY_VERIFIED);
        userRepository.save(other);
        when(firebaseContact.findPhoneOwner(phone)).thenReturn(FirebaseContactService.PhoneOwnerLookup.notFound());
        when(firebaseContact.getContacts(any())).thenAnswer(inv -> {
            java.util.Map<String, FirebaseContactService.Contact> out = new java.util.HashMap<>();
            ((java.util.Collection<String>) inv.getArgument(0)).forEach(u ->
                    out.put(u, new FirebaseContactService.Contact(null, null, phone)));
            return out;
        });

        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("declared-phone-conflict"));
    }

    @Test
    void verify_ownerLookupError_503_noWrite() throws Exception {
        // Timeout ou quota de getUserByPhoneNumber : impossible de savoir si un propriétaire SMS existe.
        when(firebaseContact.findPhoneOwner(phone)).thenReturn(FirebaseContactService.PhoneOwnerLookup.error());

        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("declared-phone-unavailable"));

        UserEntity saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getDeclaredPhoneLevel()).isEqualTo(DeclaredPhoneLevel.DECLARED);
        assertThat(saved.getDeclaredPhoneVerifiedBy()).isNull();
        verify(auditService, never()).log(any(), any(), eq("ADMIN_USER_PHONE_VERIFIED"), any(), any());
    }

    @Test
    void verify_firebaseUnavailable_503() throws Exception {
        when(firebaseContact.isAvailable()).thenReturn(false);

        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("declared-phone-unavailable"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeclaredPhoneLevel())
                .isEqualTo(DeclaredPhoneLevel.DECLARED);
    }

    @Test
    void verify_smsOwnerKeptResidualHash_409() throws Exception {
        // B a déclaré le numéro puis l'a rattaché par SMS ; l'écouteur qui efface la déclaration a échoué :
        // B garde l'empreinte, mais Firebase le désigne propriétaire SMS. Il ne doit pas passer pour un déclarant.
        UserEntity b = user("sms-residual-" + UUID.randomUUID());
        b.setDeclaredPhoneHash(hasher.hash(phone));
        b.setDeclaredPhoneLevel(DeclaredPhoneLevel.DECLARED);
        userRepository.save(b);
        when(firebaseContact.findPhoneOwner(phone))
                .thenReturn(FirebaseContactService.PhoneOwnerLookup.found(b.getFirebaseUid()));

        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("declared-phone-conflict"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeclaredPhoneLevel())
                .isEqualTo(DeclaredPhoneLevel.DECLARED);
    }

    @Test
    void verify_otherYadonyVerified_batchReadFails_stillConflict() throws Exception {
        // La lecture groupée des claims échoue (getContacts rend une map vide) : le compte déjà validé
        // par Yadony ne doit pas disparaître du contrôle. Le conflit se lit en base, sans Firebase.
        UserEntity other = user("other-down-" + UUID.randomUUID());
        other.setDeclaredPhoneHash(hasher.hash(phone));
        other.setDeclaredPhoneLevel(DeclaredPhoneLevel.YADONY_VERIFIED);
        userRepository.save(other);
        when(firebaseContact.getContacts(any())).thenReturn(new java.util.HashMap<>());

        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("declared-phone-conflict"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeclaredPhoneLevel())
                .isEqualTo(DeclaredPhoneLevel.DECLARED);
    }

    @Test
    void verify_otherSimpleDeclarant_doesNotBlock() throws Exception {
        // Un simple déclarant du même numéro ne bloque pas la validation (aucune unicité sur le déclaré).
        UserEntity squatter = user("squat-" + UUID.randomUUID());
        squatter.setDeclaredPhoneHash(hasher.hash(phone));
        squatter.setDeclaredPhoneLevel(DeclaredPhoneLevel.DECLARED);
        userRepository.save(squatter);

        mockMvc.perform(verifyShowing(phone, admin("USER_PHONE_VERIFY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declaredPhoneLevel").value("YADONY_VERIFIED"));
    }

    /** Corps = le numéro que l'admin a sous les yeux. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verifyShowing(
            String shown, RequestPostProcessor auth) {
        return post(url()).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"declaredPhone\":\"" + shown + "\"}");
    }

    /** Lecture en cache ET lecture fraîche (le service valide sur la lecture fraîche). */
    private void claim(FirebaseContactService.Contact contact) {
        when(firebaseContact.getContact(uid)).thenReturn(contact);
        when(firebaseContact.getFreshContact(uid)).thenReturn(contact);
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
