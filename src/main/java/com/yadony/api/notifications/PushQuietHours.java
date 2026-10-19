package com.yadony.api.notifications;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.UserZones;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Heures calmes et plafond quotidien des push (plan de réduction du bruit, étape 4).
 *
 * <p>De 22 h à 8 h, à l'heure du pays de résidence ({@link UserZones}), un push non critique
 * ne fait pas sonner : il reste dans le feed et compte pour le récapitulatif du matin
 * ({@link NightDigestScheduler}). Le reste de la journée, au-delà de {@link #DAILY_CAP} push non
 * critiques, les suivants restent dans le feed. Un type critique part toujours : il porte un
 * repli SMS et une échéance. Un type toujours actif ({@link NotificationPrefsService#isAlwaysOn})
 * respecte la nuit mais échappe au plafond.
 *
 * <p>Mémoire du processus (Caffeine) : un redémarrage perd au pire un récapitulatif ou remet
 * le compteur du jour à zéro. Coupé par {@code yadony.notifications.quiet-hours.enabled}.
 */
@Component
public class PushQuietHours {

    static final int QUIET_START_HOUR = 22;
    static final int QUIET_END_HOUR = 8;
    static final int DAILY_CAP = 10;

    /** Récapitulatif du matin : il part à l'heure où le calme s'arrête, jamais retenu. */
    public static final String NIGHT_DIGEST_TYPE = "NIGHT_DIGEST";

    private final Clock clock;
    private final boolean enabled;
    private final Cache<UUID, AtomicInteger> heldOvernight =
            Caffeine.newBuilder().maximumSize(200_000).expireAfterWrite(Duration.ofHours(16)).build();
    private final Cache<String, AtomicInteger> sentToday =
            Caffeine.newBuilder().maximumSize(200_000).expireAfterWrite(Duration.ofHours(26)).build();

    @Autowired
    public PushQuietHours(@Value("${yadony.notifications.quiet-hours.enabled:true}") boolean enabled) {
        this(enabled, Clock.systemUTC());
    }

    PushQuietHours(boolean enabled, Clock clock) {
        this.enabled = enabled;
        this.clock = clock;
    }

    /**
     * Le push doit-il rester dans le feed sans sonner ? Retenu la nuit (compté pour le
     * récapitulatif) ou au-delà du plafond du jour ; sinon il compte dans le plafond.
     */
    public boolean hold(UserEntity user, String type) {
        if (!enabled || user == null || user.getId() == null
                || NotificationTypes.isCritical(type) || NIGHT_DIGEST_TYPE.equals(type)) {
            return false;
        }
        ZonedDateTime local = localNow(user);
        if (isQuietHour(local.getHour())) {
            heldOvernight.get(user.getId(), id -> new AtomicInteger()).incrementAndGet();
            return true;
        }
        // Argent, identité, litiges, compte : jamais plafonnés et jamais comptés. Sans quoi
        // une contrepartie qui enchaîne les messages épuiserait le plafond de la journée et
        // ferait taire une alerte de compte ou de paiement qui suit.
        if (NotificationPrefsService.isAlwaysOn(type)) {
            return false;
        }
        String day = user.getId() + "|" + local.toLocalDate();
        return sentToday.get(day, k -> new AtomicInteger()).incrementAndGet() > DAILY_CAP;
    }

    /** Utilisateurs dont au moins un push attend le récapitulatif du matin. */
    Set<UUID> usersWithHeldPushes() {
        return Set.copyOf(heldOvernight.asMap().keySet());
    }

    /** Le calme est-il fini pour cet utilisateur, à son heure locale ? */
    boolean isMorning(UserEntity user) {
        int hour = localNow(user).getHour();
        return hour >= QUIET_END_HOUR && hour < QUIET_START_HOUR;
    }

    /** Retire et rend le nombre de push retenus pendant la nuit. */
    int takeHeld(UUID userId) {
        AtomicInteger held = heldOvernight.asMap().remove(userId);
        return held == null ? 0 : held.get();
    }

    static boolean isQuietHour(int hour) {
        return hour >= QUIET_START_HOUR || hour < QUIET_END_HOUR;
    }

    private ZonedDateTime localNow(UserEntity user) {
        return clock.instant().atZone(UserZones.zoneFor(user.getCountry()));
    }
}
