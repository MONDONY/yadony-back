package com.yadony.api.flights;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Prix d'un trajet, avec un cache de 30 minutes partagé par la recherche, les bons plans
 * et le scheduler d'alertes : les prix Aviasales sont eux-mêmes un cache rafraîchi au fil
 * des recherches, les redemander plus souvent ne les rendrait pas plus frais.
 *
 * <p><b>Couverture des compagnies.</b> Travelpayouts trie par prix et pagine : une seule page
 * de 50 offres ne contenait que des correspondances bon marché, et les vols directs plus
 * chers (Air France, Corsair sur Paris → Abidjan, Dakar, Bamako, Douala) disparaissaient.
 * Quand la recherche n'est pas limitée aux directs, on lit donc :
 * <ol>
 *   <li>la page 1 de l'appel normal (seul appel dont l'échec fait échouer la recherche) ;</li>
 *   <li>la page 2, seulement si la page 1 était pleine ;</li>
 *   <li>en parallèle, un appel {@code direct=true} dédié aux vols sans escale.</li>
 * </ol>
 * Les pages 2 et directes sont un bonus : leur échec est journalisé et la recherche rend ce
 * qu'elle a. Le tout est dédoublonné, trié par prix et tronqué à {@link #MAX_RESULTS} en gardant
 * toujours les vols directs.
 */
@Service
public class FlightPriceService {

    private static final Logger log = LoggerFactory.getLogger(FlightPriceService.class);

    /** Taille d'une page Travelpayouts (appel normal et appel des directs). */
    static final int PAGE_SIZE = 50;
    /** Pages lues pour l'appel normal : ~100 offres avec escales au plus. */
    static final int MAX_PAGES = 2;
    /** Offres rendues après fusion. */
    static final int MAX_RESULTS = 150;
    /** Garde-fou au-delà des timeouts HTTP du client (connexion 5 s + lecture 10 s). */
    private static final long DIRECT_WAIT_SECONDS = 20;

    private final TravelpayoutsClient client;
    private final Cache<String, List<TravelpayoutsPrice>> cache;
    private final Executor executor;

    @Autowired
    public FlightPriceService(TravelpayoutsClient client) {
        this(client, Duration.ofMinutes(30));
    }

    FlightPriceService(TravelpayoutsClient client, Duration ttl) {
        this(client, ttl, Executors.newVirtualThreadPerTaskExecutor());
    }

    FlightPriceService(TravelpayoutsClient client, Duration ttl, Executor executor) {
        this.client = client;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(5_000).build();
        this.executor = executor;
    }

    public boolean isAvailable() {
        return client.isConfigured();
    }

    /**
     * @throws FlightProviderException si la page 1 de l'appel principal échoue (jamais mis en cache)
     */
    public List<TravelpayoutsPrice> prices(FlightQuery query) {
        List<TravelpayoutsPrice> cached = cache.getIfPresent(query.key());
        if (cached != null) return cached;

        CompletableFuture<List<TravelpayoutsPrice>> directs = query.directOnly()
                ? CompletableFuture.completedFuture(List.of())
                : CompletableFuture.supplyAsync(() -> page(query, true, 1), executor);

        List<TravelpayoutsPrice> all = new ArrayList<>();
        List<TravelpayoutsPrice> last = page(query, query.directOnly(), 1);
        all.addAll(last);
        for (int page = 2; page <= MAX_PAGES && last.size() >= PAGE_SIZE; page++) {
            last = optional(query, page);
            all.addAll(last);
        }
        all.addAll(await(directs, query));

        List<TravelpayoutsPrice> merged = merge(all);
        cache.put(query.key(), merged);
        return merged;
    }

    private List<TravelpayoutsPrice> page(FlightQuery query, boolean direct, int page) {
        return client.pricesForDates(query.origin(), query.destination(), query.departure(),
                query.returnDate(), direct, query.currency(), PAGE_SIZE, page);
    }

    /** Page suivante de l'appel principal : un échec arrête la pagination sans faire échouer la recherche. */
    private List<TravelpayoutsPrice> optional(FlightQuery query, int page) {
        try {
            return page(query, query.directOnly(), page);
        } catch (RuntimeException e) {
            log.warn("Vols {} : page {} indisponible, on garde les pages précédentes ({})",
                    query.key(), page, e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static List<TravelpayoutsPrice> await(CompletableFuture<List<TravelpayoutsPrice>> directs,
                                                  FlightQuery query) {
        try {
            return directs.get(DIRECT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            directs.cancel(true);
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            log.warn("Vols {} : appel des vols directs indisponible, résultats sans lui ({})",
                    query.key(), cause.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * Dédoublonne (une offre peut venir de l'appel normal et de l'appel des directs), écarte les
     * prix nuls, trie par prix et tronque à {@link #MAX_RESULTS} : tous les directs d'abord, puis
     * les moins chères des autres, pour que la troncature n'évince jamais un vol direct.
     */
    static List<TravelpayoutsPrice> merge(List<TravelpayoutsPrice> rows) {
        Map<String, TravelpayoutsPrice> unique = new LinkedHashMap<>();
        for (TravelpayoutsPrice p : rows) {
            if (p == null || p.price() == null || p.price().signum() <= 0) continue;
            unique.merge(dedupKey(p), p, (a, b) -> b.price().compareTo(a.price()) < 0 ? b : a);
        }
        Comparator<TravelpayoutsPrice> byPrice = Comparator.comparing(TravelpayoutsPrice::price);
        List<TravelpayoutsPrice> direct = unique.values().stream().filter(FlightPriceService::isDirect)
                .sorted(byPrice).limit(MAX_RESULTS).toList();
        List<TravelpayoutsPrice> kept = new ArrayList<>(direct);
        unique.values().stream().filter(p -> !isDirect(p)).sorted(byPrice)
                .limit(MAX_RESULTS - direct.size()).forEach(kept::add);
        kept.sort(byPrice);
        return List.copyOf(kept);
    }

    /** Clé stable d'une offre : son chemin chez le partenaire, sinon vol + dates. */
    static String dedupKey(TravelpayoutsPrice p) {
        if (p.link() != null && !p.link().isBlank()) return "L|" + p.link();
        return "F|" + p.airline() + "|" + p.flightNumber() + "|" + p.departureAt() + "|" + p.returnAt();
    }

    /** Sans escale à l'aller et, pour un aller-retour, au retour. */
    static boolean isDirect(TravelpayoutsPrice p) {
        return p.transfers() != null && p.transfers() == 0
                && (p.returnTransfers() == null || p.returnTransfers() == 0);
    }
}
