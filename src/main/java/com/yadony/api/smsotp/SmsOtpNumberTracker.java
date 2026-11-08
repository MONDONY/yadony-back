package com.yadony.api.smsotp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Numéros pour lesquels chaque compte connecté a demandé un code SMS, sur 24 h glissantes.
 *
 * <p>Toute demande compte, même refusée ensuite (numéro inconnu, budget atteint) : c'est le
 * fait de viser plusieurs numéros qui trahit le pompage, pas le nombre de SMS partis.
 *
 * <p>En mémoire (Caffeine, une seule instance d'API comme {@code SlidingWindowRateLimiter}) :
 * un redémarrage remet les compteurs à zéro, mais la suspension qu'ils déclenchent est en base.
 */
class SmsOtpNumberTracker {

    static final Duration WINDOW = Duration.ofHours(24);

    private final Clock clock;
    private final Cache<String, Map<String, Instant>> numbersByAccount;

    SmsOtpNumberTracker(Clock clock) {
        this.clock = clock;
        this.numbersByAccount = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(WINDOW)
                .build();
    }

    /**
     * Enregistre la demande de {@code accountUid} pour {@code phoneNumber} et rend le nombre de
     * numéros différents qu'il a visés sur la fenêtre, celui-ci compris. Redemander le même
     * numéro (renvoi du code) ne compte pas deux fois.
     */
    int record(String accountUid, String phoneNumber) {
        Instant now = clock.instant();
        Instant since = now.minus(WINDOW);
        Map<String, Instant> numbers = numbersByAccount.get(accountUid, uid -> new HashMap<>());
        synchronized (numbers) {
            numbers.values().removeIf(lastAsked -> lastAsked.isBefore(since));
            numbers.put(phoneNumber, now);
            return numbers.size();
        }
    }
}
