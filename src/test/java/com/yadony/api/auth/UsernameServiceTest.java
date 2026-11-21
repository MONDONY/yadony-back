package com.yadony.api.auth;

import com.yadony.api.addressbook.invitation.LookupRateLimiter;
import com.yadony.api.auth.dto.UserResponse;
import com.yadony.api.auth.dto.UsernameAvailabilityResponse;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class UsernameServiceTest {

    private static final String UID = "uid-1";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    @Mock UserRepository userRepository;
    @Mock ReleasedUsernameRepository releasedRepository;
    @Mock jakarta.persistence.EntityManager entityManager;
    @Mock AuthService authService;
    @Mock AuditService auditService;

    LookupRateLimiter availabilityLimiter;
    UsernameService service;
    UserEntity me;
    UserResponse response;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        availabilityLimiter = new LookupRateLimiter(clock, 120);
        service = new UsernameService(userRepository, releasedRepository, authService, auditService, clock,
                availabilityLimiter);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", entityManager);
        me = new UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(me, "id", UUID.randomUUID());
        me.setFirebaseUid(UID);
        me.setUsername("Awa.Old");
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(me));
        response = org.mockito.Mockito.mock(UserResponse.class);
        when(authService.toResponse(me)).thenReturn(response);
    }

    private YadonyBusinessException thrown(Runnable r) {
        try {
            r.run();
        } catch (YadonyBusinessException e) {
            return e;
        }
        throw new AssertionError("YadonyBusinessException attendue");
    }

    @Test
    void check_libre_disponible() {
        UsernameAvailabilityResponse r = service.check(UID, "  Awa.New ");
        assertThat(r.available()).isTrue();
        assertThat(r.reason()).isNull();
        assertThat(r.suggestions()).isEmpty();
        verify(userRepository).existsByUsernameIgnoreCase("awa.new");
    }

    @Test
    void check_pris_donneSuggestions() {
        when(userRepository.existsByUsernameIgnoreCase("awa.new")).thenReturn(true);
        UsernameAvailabilityResponse r = service.check(UID, "awa.new");
        assertThat(r.available()).isFalse();
        assertThat(r.reason()).isEqualTo("TAKEN");
        assertThat(r.suggestions()).hasSizeBetween(1, 3).doesNotContain("awa.new");
    }

    @Test
    void check_libereIl10Jours_recentlyReleased() {
        when(releasedRepository.existsByUsernameAndReleasedAtAfter(eq("awa.new"), any())).thenReturn(true);
        UsernameAvailabilityResponse r = service.check(UID, "awa.new");
        assertThat(r.reason()).isEqualTo("RECENTLY_RELEASED");
        ArgumentCaptor<OffsetDateTime> threshold = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(releasedRepository).existsByUsernameAndReleasedAtAfter(eq("awa.new"), threshold.capture());
        assertThat(threshold.getValue().toInstant()).isEqualTo(NOW.minusSeconds(30L * 86400));
    }

    @Test
    void check_lesien_unchanged() {
        UsernameAvailabilityResponse r = service.check(UID, "AWA.old");
        assertThat(r.available()).isFalse();
        assertThat(r.reason()).isEqualTo("UNCHANGED");
    }

    @Test
    void check_formatEtReserve() {
        assertThat(service.check(UID, "a").reason()).isEqualTo("INVALID_FORMAT");
        assertThat(service.check(UID, "yadony.pro").reason()).isEqualTo("RESERVED");
        assertThat(service.check(UID, null).reason()).isEqualTo("INVALID_FORMAT");
    }

    @Test
    void change_valide_metAJourReleaseEtAudit() {
        UserResponse result = service.change(UID, " Awa.New ");

        assertThat(result).isSameAs(response);
        verify(userRepository).lockForUpdate(me.getId());
        verify(entityManager).refresh(me);
        assertThat(me.getUsername()).isEqualTo("awa.new");
        assertThat(me.getUsernameChangedAt().toInstant()).isEqualTo(NOW);
        ArgumentCaptor<ReleasedUsernameEntity> released = ArgumentCaptor.forClass(ReleasedUsernameEntity.class);
        verify(releasedRepository).save(released.capture());
        assertThat(released.getValue().getUsername()).isEqualTo("awa.old");
        assertThat(released.getValue().getFormerUserId()).isEqualTo(me.getId());
        assertThat(released.getValue().getReleasedAt().toInstant()).isEqualTo(NOW);
        verify(userRepository).saveAndFlush(me);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(me.getId()), eq("USERNAME_CHANGED"), eq(me.getId()),
                payload.capture());
        assertThat(payload.getValue()).containsEntry("previousHandle", "awa.old")
                .containsEntry("newHandle", "awa.new");
    }

    @Test
    void change_10JoursApresUnPrecedent_tooSoon() {
        OffsetDateTime previous = OffsetDateTime.ofInstant(NOW.minusSeconds(10L * 86400), ZoneOffset.UTC);
        me.setUsernameChangedAt(previous);

        YadonyBusinessException e = thrown(() -> service.change(UID, "awa.new"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("username-change-too-soon");
        assertThat(e.getProperties()).containsEntry("nextChangeAt", previous.plusDays(30).toString());
        verify(releasedRepository, never()).save(any());
        assertThat(me.getUsername()).isEqualTo("Awa.Old");
    }

    @Test
    void change_31JoursApres_autorise() {
        me.setUsernameChangedAt(OffsetDateTime.ofInstant(NOW.minusSeconds(31L * 86400), ZoneOffset.UTC));
        service.change(UID, "awa.new");
        assertThat(me.getUsername()).isEqualTo("awa.new");
    }

    @Test
    void change_pris_409AvecSuggestions() {
        when(userRepository.existsByUsernameIgnoreCase("awa.new")).thenReturn(true);
        when(userRepository.existsByUsernameIgnoreCase(org.mockito.ArgumentMatchers.argThat(
                s -> s != null && !s.equals("awa.new")))).thenReturn(false);

        YadonyBusinessException e = thrown(() -> service.change(UID, "awa.new"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("username-taken");
        assertThat((java.util.List<?>) e.getProperties().get("suggestions")).isNotEmpty();
        verify(releasedRepository, never()).save(any());
    }

    @Test
    void change_recemmentLibere_409Taken() {
        when(releasedRepository.existsByUsernameAndReleasedAtAfter(eq("awa.new"), any())).thenReturn(true);
        assertThat(thrown(() -> service.change(UID, "awa.new")).getErrorCode()).isEqualTo("username-taken");
    }

    @Test
    void change_courseSurIndexUnique_409Taken() {
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("ux_users_username_lower"));

        YadonyBusinessException e = thrown(() -> service.change(UID, "awa.new"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("username-taken");
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void change_formatInvalide_422_reserve_422() {
        YadonyBusinessException invalid = thrown(() -> service.change(UID, "a b"));
        assertThat(invalid.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(invalid.getErrorCode()).isEqualTo("username-invalid");
        YadonyBusinessException reserved = thrown(() -> service.change(UID, "admin.awa"));
        assertThat(reserved.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reserved.getErrorCode()).isEqualTo("username-reserved");
    }

    @Test
    void change_identiqueAuSien_sansEffet() {
        service.change(UID, "awa.old");
        verify(releasedRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void utilisateurInconnu_404() {
        when(userRepository.findByFirebaseUid("x")).thenReturn(Optional.empty());
        assertThat(thrown(() -> service.check("x", "awa")).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(thrown(() -> service.change("x", "awa.new")).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void check_121stCallInTheHour_429() {
        when(userRepository.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        for (int i = 0; i < 120; i++) {
            assertThat(service.check(UID, "libre.nom").available()).isTrue();
        }

        YadonyBusinessException e = thrown(() -> service.check(UID, "libre.nom"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(e.getErrorCode()).isEqualTo("username-availability-quota");
    }

    @Test
    void check_quotaIsIndependentFromTheLookupBucket() {
        LookupRateLimiter lookupBucket = new LookupRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC), 30);
        for (int i = 0; i < 30; i++) {
            lookupBucket.tryAcquire(me.getId());
        }
        assertThat(lookupBucket.tryAcquire(me.getId())).isFalse();

        assertThat(service.check(UID, "libre.nom").available()).isTrue();
    }

    @Test
    void check_phoneLikeIdentifier_reportsLooksLikePhone() {
        UsernameAvailabilityResponse r = service.check(UID, "0612345678");

        assertThat(r.available()).isFalse();
        assertThat(r.reason()).isEqualTo("LOOKS_LIKE_PHONE");
    }

    @Test
    void change_phoneLikeIdentifier_422LooksLikePhone() {
        YadonyBusinessException e = thrown(() -> service.change(UID, "221771234512"));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo("username-looks-like-phone");
        verify(userRepository, never()).saveAndFlush(any());
    }
}
