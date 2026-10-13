package com.yadony.api.flights;

import com.yadony.api.flights.dto.FlightAlertOffersResponse;
import com.yadony.api.flights.dto.FlightAlertResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Résultats d'une alerte prix : les vols trouvés sous son seuil, et la première vérification
 * faite juste après sa création.
 *
 * <p>Volontairement sans {@code @Transactional} : aucune connexion JDBC n'est tenue pendant les
 * appels à Travelpayouts (même règle que la recherche et le scheduler).
 */
@Service
public class FlightAlertOffersService {

    private static final Logger log = LoggerFactory.getLogger(FlightAlertOffersService.class);

    /** Vols rendus par l'écran « Vols trouvés ». */
    static final int MAX_OFFERS = 20;
    /** Attente maximale de la première vérification dans la réponse de création. */
    static final Duration FIRST_CHECK_WAIT = Duration.ofSeconds(4);

    private final FlightPriceAlertRepository alerts;
    private final FlightPriceService prices;
    private final FlightOfferMapper mapper;
    private final FlightUsers users;
    private final Clock clock;
    private final Executor executor;
    private final Duration firstCheckWait;

    @Autowired
    public FlightAlertOffersService(FlightPriceAlertRepository alerts, FlightPriceService prices,
                                    FlightOfferMapper mapper, FlightUsers users) {
        this(alerts, prices, mapper, users, Clock.systemUTC(), Executors.newVirtualThreadPerTaskExecutor(),
                FIRST_CHECK_WAIT);
    }

    FlightAlertOffersService(FlightPriceAlertRepository alerts, FlightPriceService prices, FlightOfferMapper mapper,
                             FlightUsers users, Clock clock, Executor executor, Duration firstCheckWait) {
        this.alerts = alerts;
        this.prices = prices;
        this.mapper = mapper;
        this.users = users;
        this.clock = clock;
        this.executor = executor;
        this.firstCheckWait = firstCheckWait;
    }

    /**
     * Vols de la période de l'alerte au prix inférieur ou égal au seuil, du moins cher au plus cher,
     * {@link #MAX_OFFERS} au plus. Une alerte en pause rend quand même ses vols ; une alerte dont la
     * période de départ est passée rend une liste vide sans appeler le partenaire.
     *
     * @throws com.yadony.api.common.YadonyBusinessException 404 {@code flight-alert-not-found} si
     *         l'alerte n'existe pas ou n'est pas à l'appelant, 503 {@code flights-unavailable} si le
     *         partenaire n'est pas configuré, 502 {@code flights-provider-error} s'il ne répond pas
     */
    public FlightAlertOffersResponse offers(String firebaseUid, UUID id) {
        FlightUsers.Caller caller = users.resolveAccount(firebaseUid);
        FlightPriceAlertEntity alert = alerts.findByIdAndOwnerId(id, caller.id())
                .orElseThrow(FlightErrors::alertNotFound);
        if (!prices.isAvailable()) throw FlightErrors.unavailable();

        LocalDate today = LocalDate.now(clock);
        List<TravelpayoutsPrice> found;
        if (FlightPriceAlertScheduler.isExpired(alert, today)) {
            found = List.of();
        } else {
            try {
                found = FlightAlertMatches.inWindow(alert, prices, today);
            } catch (FlightProviderException e) {
                throw FlightErrors.providerError();
            }
        }
        return new FlightAlertOffersResponse(alert.getId(), alert.getOriginCode(), alert.getDestinationCode(),
                alert.getOriginLabel(), alert.getDestinationLabel(), alert.getReturnFrom() != null,
                alert.getCurrency(), alert.getMaxPrice(),
                FlightAlertMatches.underThreshold(found, alert.getMaxPrice()).stream().limit(MAX_OFFERS)
                        .map(p -> mapper.toDto(p, alert.getCurrency(), caller.language())).toList(),
                searchPath(alert, today), Instant.now(clock));
    }

    /**
     * Première vérification, silencieuse, d'une alerte qui vient d'être créée : remplit
     * {@code lastSeenPrice} et le nombre de vols sous le seuil pour que l'app les affiche tout
     * de suite. Aucune notification, et l'anti-spam n'est pas touché : si un prix est déjà sous
     * le seuil, la prochaine exécution du scheduler l'annoncera comme d'habitude.
     *
     * <p>La réponse attend au plus {@link #FIRST_CHECK_WAIT} ; au-delà, la vérification se
     * termine en arrière-plan et l'alerte est rendue telle quelle. Toute erreur est avalée :
     * la création a déjà réussi.
     */
    public FlightAlertResponse firstCheck(FlightAlertResponse created) {
        if (!prices.isAvailable()) return created;
        CompletableFuture<FlightAlertResponse> check;
        try {
            check = CompletableFuture.supplyAsync(() -> check(created), executor);
        } catch (RuntimeException e) {
            log.warn("[FlightAlert] première vérification de {} non lancée : {}", created.id(), e.getMessage());
            return created;
        }
        try {
            return check.get(firstCheckWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return created;
        } catch (TimeoutException e) {
            log.info("[FlightAlert] première vérification de {} encore en cours, réponse sans elle", created.id());
            return created;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.info("[FlightAlert] première vérification de {} impossible : {}", created.id(), cause.getMessage());
            return created;
        }
    }

    /**
     * Mise à jour ciblée, seulement si l'alerte n'a encore jamais été vérifiée : ni une
     * modification faite entre-temps par l'utilisateur (pause, seuil, suppression), ni une
     * vérification du scheduler ne sont écrasées.
     */
    private FlightAlertResponse check(FlightAlertResponse created) {
        FlightPriceAlertEntity alert = alerts.findById(created.id()).orElse(null);
        if (alert == null || alert.getLastCheckedAt() != null) return created;
        List<TravelpayoutsPrice> found = FlightAlertMatches.inWindow(alert, prices, LocalDate.now(clock));
        FlightAlertMatches.record(alert, found);
        alert.setLastSeenPrice(FlightAlertMatches.cheapest(found));
        alert.setLastCheckedAt(LocalDateTime.now(clock));
        int updated = alerts.recordFirstCheck(alert.getId(), alert.getLastSeenPrice(), alert.getLastCheckedAt(),
                alert.getLastMatchCount(), alert.getLastMatchAirlines());
        return updated == 1 ? FlightAlertService.toResponse(alert) : created;
    }

    /** Chemin Aviasales sur le premier jour (encore à venir) de la période, {@code null} sans période de départ. */
    static String searchPath(FlightPriceAlertEntity alert, LocalDate today) {
        FlightAlertWindow window = alert.window().from(today);
        if (window.departureFrom() == null) return null;
        FlightQuery query = new FlightQuery(alert.getOriginCode(), alert.getDestinationCode(),
                FlightDate.ofDay(window.departureFrom()),
                window.returnFrom() == null ? null : FlightDate.ofDay(window.returnFrom()),
                alert.isDirectOnly(), alert.getCurrency());
        return query.searchPath(today);
    }
}
