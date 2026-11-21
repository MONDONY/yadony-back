package com.yadony.api.addressbook.invitation;

import com.yadony.api.addressbook.invitation.dto.UsernameLookupResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsernameLookupServiceTest {

    private static final String UID = "uid-me";

    @Mock UserRepository userRepository;
    @Mock BlockVisibility blockVisibility;
    @Mock LookupRateLimiter rateLimiter;

    UsernameLookupService service;
    UserEntity me;

    @BeforeEach
    void setUp() {
        service = new UsernameLookupService(userRepository, blockVisibility, rateLimiter);
        me = user("Moi", "moi.moi");
        lenient().when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(me));
        lenient().when(rateLimiter.tryAcquire(any())).thenReturn(true);
    }

    private static UserEntity user(String firstName, String username) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Secret");
        u.setUsername(username);
        return u;
    }

    @Test
    void foundReturnsUsernameAndInitialOnly() {
        UserEntity awa = user("awa", "awa.diallo");
        when(userRepository.findByUsernameIgnoreCase("awa.diallo")).thenReturn(Optional.of(awa));
        when(blockVisibility.isHidden(me.getId(), awa.getId())).thenReturn(false);

        assertThat(service.lookup(UID, "AWA.Diallo ")).isEqualTo(new UsernameLookupResponse("awa.diallo", "A"));
    }

    @Test
    void missingFirstNameGivesQuestionMark() {
        UserEntity noName = user(null, "sans.nom");
        when(userRepository.findByUsernameIgnoreCase("sans.nom")).thenReturn(Optional.of(noName));

        assertThat(service.lookup(UID, "sans.nom").initial()).isEqualTo("?");
    }

    @Test
    void existingAccountWithReservedWordIsFound() {
        UserEntity support = user("Awa", "support.awa");
        when(userRepository.findByUsernameIgnoreCase("support.awa")).thenReturn(Optional.of(support));

        assertThat(service.lookup(UID, "support.awa")).isEqualTo(new UsernameLookupResponse("support.awa", "A"));
    }

    @Test
    void initialHandlesAccentsAndNonBmpFirstNames() {
        UserEntity accent = user("élodie", "elodie.k");
        UserEntity emoji = user("\uD83D\uDE00 Fun", "fun.k");
        when(userRepository.findByUsernameIgnoreCase("elodie.k")).thenReturn(Optional.of(accent));
        when(userRepository.findByUsernameIgnoreCase("fun.k")).thenReturn(Optional.of(emoji));

        assertThat(service.lookup(UID, "elodie.k").initial()).isEqualTo("\u00C9");
        assertThat(service.lookup(UID, "fun.k").initial()).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void unknownIsNotFound() {
        when(userRepository.findByUsernameIgnoreCase("inconnu")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lookup(UID, "inconnu"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("user-not-found");
                });
    }

    @Test
    void invalidFormatIsNotFoundWithoutSearching() {
        assertThatThrownBy(() -> service.lookup(UID, "a b"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("user-not-found"));
        verify(userRepository, never()).findByUsernameIgnoreCase(any());
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void suspendedOrBannedAccountIsNotFound() {
        UserEntity suspended = user("Awa", "awa.diallo");
        suspended.setStatus(UserStatus.SUSPENDED);
        UserEntity banned = user("Moussa", "moussa.k");
        banned.setStatus(UserStatus.BANNED);
        when(userRepository.findByUsernameIgnoreCase("awa.diallo")).thenReturn(Optional.of(suspended));
        when(userRepository.findByUsernameIgnoreCase("moussa.k")).thenReturn(Optional.of(banned));

        assertThat(service.resolveVisible(me, "awa.diallo")).isEmpty();
        assertThat(service.resolveVisible(me, "moussa.k")).isEmpty();
    }

    @Test
    void hiddenByBlockIsNotFound() {
        UserEntity awa = user("Awa", "awa.diallo");
        when(userRepository.findByUsernameIgnoreCase("awa.diallo")).thenReturn(Optional.of(awa));
        when(blockVisibility.isHidden(me.getId(), awa.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.lookup(UID, "awa.diallo"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("user-not-found"));
    }

    @Test
    void selfIsUnprocessable() {
        when(userRepository.findByUsernameIgnoreCase("moi.moi")).thenReturn(Optional.of(me));

        assertThatThrownBy(() -> service.lookup(UID, "moi.moi"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("recipient-invitation-self");
                });
    }

    @Test
    void beginLookupChecksFormatBeforeCountingQuota() {
        assertThatThrownBy(() -> service.beginLookup(me.getId(), "a b"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("user-not-found"));
        verifyNoInteractions(rateLimiter);

        service.beginLookup(me.getId(), "Awa.Diallo");
        verify(rateLimiter).tryAcquire(me.getId());
    }

    @Test
    void quotaExceededIsRejectedBeforeSearching() {
        when(rateLimiter.tryAcquire(me.getId())).thenReturn(false);

        assertThatThrownBy(() -> service.lookup(UID, "awa.diallo"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getErrorCode()).isEqualTo("recipient-lookup-quota");
                });
        verifyNoInteractions(blockVisibility);
        verify(userRepository, never()).findByUsernameIgnoreCase(any());
    }

    @Test
    void resolveVisibleIsEmptyForInvalidUnknownOrHidden() {
        UserEntity awa = user("Awa", "awa.diallo");
        when(userRepository.findByUsernameIgnoreCase("awa.diallo")).thenReturn(Optional.of(awa));
        when(blockVisibility.isHidden(me.getId(), awa.getId())).thenReturn(true);

        assertThat(service.resolveVisible(me, "awa.diallo")).isEmpty();
        assertThat(service.resolveVisible(me, "!!")).isEmpty();
        assertThat(service.resolveVisible(me, null)).isEmpty();
    }

    @Test
    void resolveVisibleReturnsUserEvenWhenSelf() {
        when(userRepository.findByUsernameIgnoreCase("moi.moi")).thenReturn(Optional.of(me));

        assertThat(service.resolveVisible(me, "MOI.moi")).containsSame(me);
    }
}
