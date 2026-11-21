package com.yadony.api.admin;

import com.yadony.api.auth.DeclaredPhoneHasher;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AdminDeclaredPhoneService")
class AdminDeclaredPhoneServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final FirebaseContactService firebase = mock(FirebaseContactService.class);
    private final AuditService audit = mock(AuditService.class);

    @Test
    void verify_unknownUser_404() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());
        var service = new AdminDeclaredPhoneService(users, firebase, new DeclaredPhoneHasher("k"), audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class);
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void verify_hasherNotConfigured_declaredPhoneMissing_noWrite() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("u");
        user.setDeclaredPhoneHash("h");
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(firebase.getContact("u")).thenReturn(new FirebaseContactService.Contact(null, null, "+2250707070707"));
        var service = new AdminDeclaredPhoneService(users, firebase, new DeclaredPhoneHasher(""), audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("declared-phone-missing"));
        verify(users, never()).saveAndFlush(any());
    }
}
