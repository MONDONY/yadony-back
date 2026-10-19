package com.yadony.api.smsotp;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.FirebaseContactService.DeletionOutcome;
import com.yadony.api.auth.FirebaseContactService.PhoneHolder;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /auth/sms-otp/attach} de bout en bout (vrai service, vraie base H2, Firebase
 * simulé) : numéro porté par un autre compte Firebase (YADONY-FLUTTER-PROD-Y).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("POST /auth/sms-otp/attach — numéro porté par un autre compte (PROD-Y)")
class SmsOtpAttachOwnershipIntegrationTest {

    private static final String CODE = "123456";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired SmsOtpRepository smsOtpRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AuditService auditService;
    @MockitoBean FirebaseContactService firebaseContact;

    private String phone;
    private String myUid;
    private String holderUid;
    private UserEntity me;

    @BeforeEach
    void seed() {
        // Numéro et UID propres à chaque test : rien ne dépend de l'ordre ni du reste du contexte.
        String suffix = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000L));
        phone = "+22170" + "0".repeat(7 - suffix.length()) + suffix;
        myUid = "attach-me-" + UUID.randomUUID();
        holderUid = "attach-holder-" + UUID.randomUUID();
        me = user(myUid, UserStatus.ACTIVE);

        SmsOtpEntity otp = new SmsOtpEntity();
        otp.setPhoneNumber(phone);
        otp.setCodeHash(passwordEncoder.encode(CODE));
        otp.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        smsOtpRepository.save(otp);

        when(firebaseContact.getContact(myUid)).thenReturn(FirebaseContactService.Contact.EMPTY);
        when(firebaseContact.findPhoneHolder(phone))
                .thenReturn(Optional.of(new PhoneHolder(holderUid, null, Set.of("phone"))));
    }

    @Test
    @DisplayName("porteur en cours de suppression → 409 phone-pending-deletion en application/problem+json")
    void holderPendingDeletion_409ProblemJson() throws Exception {
        user(holderUid, UserStatus.PENDING_DELETION);

        mockMvc.perform(attach())
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("phone-pending-deletion"))
                .andExpect(jsonPath("$.detail").value(
                        "Ce numéro appartient à un compte en cours de suppression. "
                                + "Connectez-vous avec ce numéro pour annuler la suppression."));

        verify(firebaseContact, never()).deleteAccount(anyString());
        verify(firebaseContact, never()).updatePhone(anyString(), anyString());
    }

    @Test
    @DisplayName("porteur actif → 409 phone-already-exists avec un détail qui oriente")
    void holderActive_409AlreadyExists() throws Exception {
        user(holderUid, UserStatus.ACTIVE);

        mockMvc.perform(attach())
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("phone-already-exists"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("regrouper vos comptes")));

        verify(firebaseContact, never()).deleteAccount(anyString());
    }

    @Test
    @DisplayName("fantôme sans compte Yadony → 200, fantôme supprimé, numéro rattaché, audit USER_PHONE_RECLAIMED sans numéro")
    void phantom_reclaimed_200() throws Exception {
        when(firebaseContact.deleteAccount(holderUid)).thenReturn(DeletionOutcome.DELETED);

        mockMvc.perform(attach()).andExpect(status().isOk());

        verify(firebaseContact).deleteAccount(holderUid);
        verify(firebaseContact).updatePhone(myUid, phone);
        verify(auditService).log(org.mockito.ArgumentMatchers.eq("USER"), org.mockito.ArgumentMatchers.eq(me.getId()),
                org.mockito.ArgumentMatchers.eq("USER_PHONE_RECLAIMED"), org.mockito.ArgumentMatchers.eq(me.getId()),
                org.mockito.ArgumentMatchers.argThat(payload -> holderUid.equals(payload.get("releasedFirebaseUid"))
                        && !payload.toString().contains(phone)
                        && !payload.toString().contains(phone.substring(4))));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder attach() {
        return post("/auth/sms-otp/attach")
                .with(as(myUid))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phone + "\",\"code\":\"" + CODE + "\"}");
    }

    private static RequestPostProcessor as(String uid) {
        return authentication(new UsernamePasswordAuthenticationToken(uid, null, List.of()));
    }

    private UserEntity user(String uid, UserStatus status) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setStatus(status);
        u.setRoles(new HashSet<>(List.of(Role.SENDER)));
        return userRepository.save(u);
    }
}
