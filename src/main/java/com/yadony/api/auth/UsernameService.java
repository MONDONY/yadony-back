package com.yadony.api.auth;

import com.yadony.api.addressbook.invitation.LookupRateLimiter;
import com.yadony.api.auth.dto.UserResponse;
import com.yadony.api.auth.dto.UsernameAvailabilityResponse;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Vérification de disponibilité et changement de l'identifiant public (username).
 *
 * <p>Un changement par fenêtre de {@link #CHANGE_INTERVAL}. L'ancien identifiant est réservé
 * {@link #RESERVATION} dans {@code released_usernames}, y compris pour son ancien titulaire, pour
 * qu'un tiers ne se fasse pas passer pour lui. Tous les identifiants sont comparés en minuscules.
 */
@Service
public class UsernameService {

    /** Délai minimal entre deux changements d'identifiant d'un même compte. */
    public static final Duration CHANGE_INTERVAL = Duration.ofDays(30);

    /** Durée pendant laquelle un identifiant libéré n'est repris par personne. */
    public static final Duration RESERVATION = Duration.ofDays(30);

    static final String AUDIT_ACTION = "USERNAME_CHANGED";

    private final UserRepository userRepository;
    private final ReleasedUsernameRepository releasedRepository;
    private final AuthService authService;
    private final AuditService auditService;
    private final Clock clock;
    private final LookupRateLimiter availabilityLimiter;

    @PersistenceContext
    private EntityManager entityManager;

    public UsernameService(UserRepository userRepository,
                           ReleasedUsernameRepository releasedRepository,
                           AuthService authService,
                           AuditService auditService,
                           Clock clock,
                           @Qualifier("usernameAvailabilityRateLimiter") LookupRateLimiter availabilityLimiter) {
        this.userRepository = userRepository;
        this.releasedRepository = releasedRepository;
        this.authService = authService;
        this.auditService = auditService;
        this.clock = clock;
        this.availabilityLimiter = availabilityLimiter;
    }

    @Transactional(readOnly = true)
    public UsernameAvailabilityResponse check(String firebaseUid, String raw) {
        UserEntity me = loadUser(userRepository.findByFirebaseUid(firebaseUid));
        // La réponse (TAKEN, RECENTLY_RELEASED) révèle l'existence d'un compte : 120 vérifications par heure
        // et par utilisateur, compté avant toute lecture.
        if (!availabilityLimiter.tryAcquire(me.getId())) {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "username-availability-quota",
                    "problem.username-availability-quota");
        }
        String candidate = UsernamePolicy.normalize(raw);

        if (candidate.equals(lower(me.getUsername()))) {
            return new UsernameAvailabilityResponse(false, "UNCHANGED", List.of());
        }
        Optional<UsernamePolicy.Violation> violation = UsernamePolicy.violation(candidate);
        if (violation.isPresent()) {
            return new UsernameAvailabilityResponse(false, violation.get().name(), List.of());
        }
        OffsetDateTime threshold = reservationThreshold();
        if (userRepository.existsByUsernameIgnoreCase(candidate)) {
            return new UsernameAvailabilityResponse(false, "TAKEN", suggestions(candidate, threshold));
        }
        if (releasedRepository.existsByUsernameAndReleasedAtAfter(candidate, threshold)) {
            return new UsernameAvailabilityResponse(false, "RECENTLY_RELEASED",
                    suggestions(candidate, threshold));
        }
        return new UsernameAvailabilityResponse(true, null, List.of());
    }

    @Transactional
    public UserResponse change(String firebaseUid, String raw) {
        String candidate = UsernamePolicy.normalize(raw);
        Optional<UsernamePolicy.Violation> violation = UsernamePolicy.violation(candidate);
        if (violation.isPresent()) {
            throw switch (violation.get()) {
                case RESERVED -> YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY,
                        "username-reserved", "problem.username-reserved");
                case LOOKS_LIKE_PHONE -> YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY,
                        "username-looks-like-phone", "problem.username-looks-like-phone");
                case INVALID_FORMAT -> YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY,
                        "username-invalid", "problem.username-invalid");
            };
        }

        // Verrou de ligne par UPDATE neutre (exécutable sous H2, contrairement à
        // findByFirebaseUidForUpdate), puis relecture : deux changements simultanés du même compte
        // doivent voir le usernameChangedAt posé par le premier.
        UserEntity user = loadUser(userRepository.findByFirebaseUid(firebaseUid));
        userRepository.lockForUpdate(user.getId());
        entityManager.refresh(user);
        String previous = lower(user.getUsername());
        if (candidate.equals(previous)) {
            return authService.toResponse(user);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime changedAt = user.getUsernameChangedAt();
        if (changedAt != null && now.isBefore(changedAt.plus(CHANGE_INTERVAL))) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "username-change-too-soon",
                    "problem.username-change-too-soon",
                    Map.of("nextChangeAt", changedAt.plus(CHANGE_INTERVAL).toString()));
        }

        OffsetDateTime threshold = reservationThreshold();
        if (userRepository.existsByUsernameIgnoreCase(candidate)
                || releasedRepository.existsByUsernameAndReleasedAtAfter(candidate, threshold)) {
            throw taken(suggestions(candidate, threshold));
        }

        try {
            releasedRepository.save(new ReleasedUsernameEntity(previous, now, user.getId()));
            user.setUsername(candidate);
            user.setUsernameChangedAt(now);
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException race) {
            // Course : un autre compte a pris l'identifiant entre la lecture et l'écriture
            // (index unique sur lower(username)).
            throw taken(List.of());
        }

        // Les clés ne finissent pas par « name » : AuditService masquerait la valeur.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previousHandle", previous);
        payload.put("newHandle", candidate);
        auditService.log("USER", user.getId(), AUDIT_ACTION, user.getId(), payload);
        return authService.toResponse(user);
    }

    private OffsetDateTime reservationThreshold() {
        return OffsetDateTime.now(clock).minus(RESERVATION);
    }

    private List<String> suggestions(String base, OffsetDateTime threshold) {
        return UsernameSuggester.suggest(base, c -> !userRepository.existsByUsernameIgnoreCase(c)
                && !releasedRepository.existsByUsernameAndReleasedAtAfter(c, threshold));
    }

    private static YadonyBusinessException taken(List<String> suggestions) {
        return YadonyBusinessException.localized(HttpStatus.CONFLICT, "username-taken",
                "problem.username-taken", Map.of("suggestions", suggestions));
    }

    private static UserEntity loadUser(Optional<UserEntity> user) {
        return user.orElseThrow(() -> YadonyBusinessException.localized(
                HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
