package com.yadony.api.notifications;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Délais de calme entre deux push d'un même flux (plan de réduction du bruit, étape 3).
 * Après un push, les suivants du même flux restent dans le feed (le chat garde ses messages)
 * sans faire sonner, le temps du délai. Le premier push a déjà amené la personne à ouvrir
 * l'app, où elle retrouve tout ; au-delà du délai, le push suivant remplace l'ancien grâce à
 * {@link PushCollapseKey}.
 *
 * <p>Mémoire du processus (Caffeine) : un redémarrage remet les délais à zéro, ce qui coûte au
 * pire un push de plus. Un type critique n'est jamais retenu : il porte un repli SMS.
 */
@Component
public class PushThrottle {

    enum Rule {
        /** Conversation, ticket support, fil de négociation : une sonnerie, puis le calme. */
        CHAT(Duration.ofMinutes(5)),
        /** Demandes sur un même trajet : le voyageur ouvre l'app et les voit toutes. */
        NEW_BIDS(Duration.ofMinutes(15)),
        /** Même événement pour plusieurs colis d'une personne (arrivée, annulation...). */
        BURST(Duration.ofMinutes(2));

        final Duration window;

        Rule(Duration window) {
            this.window = window;
        }
    }

    /** Types émis en série pour une même personne par un seul événement. */
    static final Set<String> BURST_TYPES = Set.of("TRIP_ARRIVED", "TRIP_CANCELLED", "BID_EXPIRED", "BID_REJECTED");

    private final Map<Rule, Cache<String, Boolean>> recent = new EnumMap<>(Rule.class);

    @Autowired
    public PushThrottle() {
        this(Ticker.systemTicker());
    }

    PushThrottle(Ticker ticker) {
        for (Rule rule : Rule.values()) {
            recent.put(rule, Caffeine.newBuilder()
                    .ticker(ticker)
                    .maximumSize(200_000)
                    .expireAfterWrite(rule.window)
                    .build());
        }
    }

    /**
     * Le push doit-il être retenu ? Le premier de son flux passe et ouvre le délai ; les suivants
     * sont retenus jusqu'à son expiration. Atomique : deux envois simultanés n'en laissent
     * passer qu'un.
     */
    public boolean hold(UUID userId, String type, Map<String, String> data) {
        if (userId == null || type == null || NotificationTypes.isCritical(type)) {
            return false;
        }
        return streamFor(type, data == null ? Map.of() : data)
                .map(stream -> recent.get(stream.rule()).asMap()
                        .putIfAbsent(userId + "|" + stream.key(), Boolean.TRUE) != null)
                .orElse(false);
    }

    record Stream(Rule rule, String key) {}

    static Optional<Stream> streamFor(String type, Map<String, String> data) {
        return switch (type) {
            case "NEW_MESSAGE", "SUPPORT_MESSAGE", "bid_negotiation_message", "negotiation_counter" ->
                    PushCollapseKey.of(type, data).map(key -> new Stream(Rule.CHAT, key));
            case "BID_CREATED" ->
                    NotificationGroupKey.of(type, data).map(key -> new Stream(Rule.NEW_BIDS, key));
            default -> BURST_TYPES.contains(type)
                    ? Optional.of(new Stream(Rule.BURST, type))
                    : Optional.empty();
        };
    }
}
