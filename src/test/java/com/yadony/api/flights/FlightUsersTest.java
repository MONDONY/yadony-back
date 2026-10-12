package com.yadony.api.flights;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FlightUsersTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final FlightUsers users = new FlightUsers(repository);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
    }

    private static void authenticateAs(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("uid", null,
                List.of(new SimpleGrantedAuthority(authority))));
    }

    @Test
    void guest_isAnonymous_withoutTouchingTheUsersTable_andUsesAcceptLanguage() {
        authenticateAs("ROLE_GUEST");
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        FlightUsers.Caller caller = users.resolve("uid");

        assertThat(caller.anonymous()).isTrue();
        assertThat(caller.language()).isEqualTo("en");
        verifyNoInteractions(repository);
    }

    @Test
    void guest_cannotUseAccountOnlyActions() {
        authenticateAs("ROLE_GUEST");

        assertThatThrownBy(() -> users.resolveAccount("uid"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("flights-account-required");
    }

    @Test
    void member_isResolvedFromTheDatabase() {
        authenticateAs("ROLE_SENDER");
        UserEntity user = mock(UserEntity.class);
        when(user.getId()).thenReturn(java.util.UUID.randomUUID());
        when(user.getPreferredLanguage()).thenReturn(com.yadony.api.common.i18n.AppLanguage.FR);
        when(repository.findByFirebaseUid("uid")).thenReturn(Optional.of(user));

        FlightUsers.Caller caller = users.resolveAccount("uid");

        assertThat(caller.anonymous()).isFalse();
        assertThat(caller.id()).isEqualTo(user.getId());
        assertThat(caller.language()).isEqualTo("fr");
    }

    @Test
    void unknownMember_is404() {
        authenticateAs("ROLE_SENDER");
        when(repository.findByFirebaseUid("uid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> users.resolve("uid")).isInstanceOf(YadonyNotFoundException.class);
    }
}
