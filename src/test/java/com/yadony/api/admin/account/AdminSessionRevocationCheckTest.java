package com.yadony.api.admin.account;

import com.google.firebase.ErrorCode;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSessionRevocationCheckTest {

    private final AdminSessionRevocationCheck check = new AdminSessionRevocationCheck();
    private final FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);

    private static FirebaseAuthException authError(AuthErrorCode code) {
        return new FirebaseAuthException(ErrorCode.UNAUTHENTICATED, "x", null, null, code);
    }

    @Test
    void validSession_isVerifiedWithRevocationCheck_thenCached() throws Exception {
        when(firebaseAuth.verifyIdToken("tok", true)).thenReturn(mock(FirebaseToken.class));
        try (MockedStatic<FirebaseAuth> st = mockStatic(FirebaseAuth.class)) {
            st.when(FirebaseAuth::getInstance).thenReturn(firebaseAuth);

            assertThat(check.check("tok")).isEqualTo(AdminSessionRevocationCheck.Result.VALID);
            assertThat(check.check("tok")).isEqualTo(AdminSessionRevocationCheck.Result.VALID);
        }
        // Un seul appel réseau pour deux requêtes : le second passe par le cache de 60 s.
        verify(firebaseAuth, times(1)).verifyIdToken("tok", true);
    }

    @ParameterizedTest
    @EnumSource(value = AuthErrorCode.class,
            names = {"REVOKED_ID_TOKEN", "USER_DISABLED", "USER_NOT_FOUND", "EXPIRED_ID_TOKEN"})
    void revokedOrDisabledSession_isRevoked(AuthErrorCode code) throws Exception {
        when(firebaseAuth.verifyIdToken("tok", true)).thenThrow(authError(code));
        try (MockedStatic<FirebaseAuth> st = mockStatic(FirebaseAuth.class)) {
            st.when(FirebaseAuth::getInstance).thenReturn(firebaseAuth);

            assertThat(check.check("tok")).isEqualTo(AdminSessionRevocationCheck.Result.REVOKED);
        }
    }

    @Test
    void otherFirebaseFailure_isUnavailable_neverValid() throws Exception {
        when(firebaseAuth.verifyIdToken("tok", true))
                .thenThrow(new FirebaseAuthException(ErrorCode.UNAVAILABLE, "down", null, null, null));
        try (MockedStatic<FirebaseAuth> st = mockStatic(FirebaseAuth.class)) {
            st.when(FirebaseAuth::getInstance).thenReturn(firebaseAuth);

            assertThat(check.check("tok")).isEqualTo(AdminSessionRevocationCheck.Result.UNAVAILABLE);
        }
    }

    @Test
    void forgetVerifiedSessions_forcesANewRevocationCheck() throws Exception {
        when(firebaseAuth.verifyIdToken("tok", true))
                .thenReturn(mock(FirebaseToken.class))
                .thenThrow(authError(AuthErrorCode.REVOKED_ID_TOKEN));
        try (MockedStatic<FirebaseAuth> st = mockStatic(FirebaseAuth.class)) {
            st.when(FirebaseAuth::getInstance).thenReturn(firebaseAuth);

            assertThat(check.check("tok")).isEqualTo(AdminSessionRevocationCheck.Result.VALID);
            check.forgetVerifiedSessions();
            assertThat(check.check("tok")).isEqualTo(AdminSessionRevocationCheck.Result.REVOKED);
        }
    }
}
