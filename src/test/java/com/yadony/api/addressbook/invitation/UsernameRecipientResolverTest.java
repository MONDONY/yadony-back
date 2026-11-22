package com.yadony.api.addressbook.invitation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.RecipientAccount;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsernameRecipientResolverTest {

    @Mock UserRepository userRepository;
    @Mock BlockVisibility blockVisibility;

    UsernameRecipientResolver resolver;
    UserEntity me;

    @BeforeEach
    void setUp() {
        // Vrai limiteur et vrai service de recherche : on vérifie le seau partagé de 30 par heure.
        LookupRateLimiter limiter = new LookupRateLimiter(Clock.systemUTC(), LookupRateLimiter.MAX_PER_HOUR);
        UsernameLookupService lookup = new UsernameLookupService(userRepository, blockVisibility, limiter);
        resolver = new UsernameRecipientResolver(lookup, userRepository);
        me = user("moi.moi");
        lenient().when(userRepository.findById(me.getId())).thenReturn(Optional.of(me));
    }

    private static UserEntity user(String username) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setUsername(username);
        return u;
    }

    @Test
    void foundAccountIsReturned() {
        UserEntity awa = user("awa.diallo");
        when(userRepository.findByUsernameIgnoreCase("awa.diallo")).thenReturn(Optional.of(awa));

        assertThat(resolver.resolve(me.getId(), "awa.diallo"))
                .isEqualTo(new RecipientAccount(awa.getId(), "awa.diallo"));
    }

    @Test
    void lookupIsCaseInsensitive() {
        UserEntity awa = user("awa.diallo");
        when(userRepository.findByUsernameIgnoreCase("awa.diallo")).thenReturn(Optional.of(awa));

        assertThat(resolver.resolve(me.getId(), " AWA.Diallo ").userId()).isEqualTo(awa.getId());
    }

    @Test
    void unknownIsNotFound() {
        when(userRepository.findByUsernameIgnoreCase("inconnu.x")).thenReturn(Optional.empty());

        assertStatus(() -> resolver.resolve(me.getId(), "inconnu.x"), HttpStatus.NOT_FOUND, "user-not-found");
    }

    @Test
    void blockedIsNotFound() {
        UserEntity awa = user("awa.diallo");
        when(userRepository.findByUsernameIgnoreCase("awa.diallo")).thenReturn(Optional.of(awa));
        when(blockVisibility.isHidden(me.getId(), awa.getId())).thenReturn(true);

        assertStatus(() -> resolver.resolve(me.getId(), "awa.diallo"), HttpStatus.NOT_FOUND, "user-not-found");
    }

    @Test
    void suspendedIsNotFound() {
        UserEntity awa = user("awa.diallo");
        awa.setStatus(UserStatus.SUSPENDED);
        when(userRepository.findByUsernameIgnoreCase("awa.diallo")).thenReturn(Optional.of(awa));

        assertStatus(() -> resolver.resolve(me.getId(), "awa.diallo"), HttpStatus.NOT_FOUND, "user-not-found");
    }

    @Test
    void selfIsRejected() {
        when(userRepository.findByUsernameIgnoreCase("moi.moi")).thenReturn(Optional.of(me));

        assertStatus(() -> resolver.resolve(me.getId(), "moi.moi"), HttpStatus.UNPROCESSABLE_ENTITY,
                "recipient-invitation-self");
    }

    @Test
    void thirtyFirstCallInTheHourIsRateLimited() {
        when(userRepository.findByUsernameIgnoreCase("inconnu.x")).thenReturn(Optional.empty());
        for (int i = 0; i < LookupRateLimiter.MAX_PER_HOUR; i++) {
            assertStatus(() -> resolver.resolve(me.getId(), "inconnu.x"), HttpStatus.NOT_FOUND, "user-not-found");
        }

        assertStatus(() -> resolver.resolve(me.getId(), "inconnu.x"), HttpStatus.TOO_MANY_REQUESTS,
                "recipient-lookup-quota");
    }

    @Test
    void invalidFormatDoesNotConsumeQuota() {
        when(userRepository.findByUsernameIgnoreCase("inconnu.x")).thenReturn(Optional.empty());
        for (int i = 0; i < 50; i++) {
            assertStatus(() -> resolver.resolve(me.getId(), "!!"), HttpStatus.NOT_FOUND, "user-not-found");
        }

        // Le plafond est intact : 30 vraies recherches passent encore.
        for (int i = 0; i < LookupRateLimiter.MAX_PER_HOUR; i++) {
            assertStatus(() -> resolver.resolve(me.getId(), "inconnu.x"), HttpStatus.NOT_FOUND, "user-not-found");
        }
    }

    private static void assertStatus(Runnable call, HttpStatus status, String type) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getErrorCode()).isEqualTo(type);
        });
    }
}
