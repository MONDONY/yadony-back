package com.yadony.api.flights;

import com.yadony.api.flights.dto.FlightClickRequest;
import com.yadony.api.flights.dto.FlightClickResponse;
import com.yadony.api.flights.dto.FlightPassengers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforme l'offre choisie en lien affilié et enregistre le clic.
 *
 * <p>Le domaine du lien est le nôtre (configuration), jamais celui envoyé par l'app :
 * {@code offerPath} n'est accepté que s'il ressemble à un chemin de recherche du
 * partenaire, ce qui ferme toute redirection vers un autre site. Il accepte le chemin d'une
 * offre ({@code /search/PAR0412ABJ1?t=...}) comme le chemin de recherche du trajet rendu par
 * {@code GET /flights/search} ({@code searchPath}, ex. {@code /search/PAR1212ABJ1}) : toujours
 * relatif, commençant par {@code /search/}, sans {@code //} ni {@code ..}.
 *
 * <p>Sous-identifiant : quand {@code subid-enabled} est vrai, le marker devient
 * {@code <marker>.<subId>} pour retrouver le clic dans les statistiques Travelpayouts.
 * Désactivé par défaut tant que le format n'est pas validé sur le compte partenaire :
 * un marker mal formé ferait perdre la commission.
 *
 * <p>Devise : sans paramètre, aviasales.com s'ouvre en dollars US même quand l'app affichait des
 * euros. Le lien porte donc {@code currency} (en minuscules), celle de l'offre si le site la gère
 * ({@link #SITE_CURRENCIES}, constaté le 13/10/2026 : {@code xof}, {@code xaf}, {@code mad} et
 * {@code chf} y retombent en USD), sinon l'euro (XOF et XAF y sont arrimés à taux fixe). Pas de
 * paramètre de langue : {@code locale}, {@code language} et {@code lang} n'ont aucun effet sur
 * aviasales.com, dont la langue suit le domaine.
 *
 * <p>Voyageurs : le chemin Aviasales finit par la classe (lettre facultative) puis un chiffre par
 * type de voyageur, adultes, enfants, bébés ({@code PAR1607NYC2007c321} : affaires, 3 adultes,
 * 2 enfants, 1 bébé ; doc Travelpayouts « Aviasales affiliate links »). Les chiffres nuls de fin
 * sont omis, comme le {@code 1} d'un adulte seul. Les offres et les prix restent ceux d'un adulte :
 * quand l'app envoie une autre composition, seul ce suffixe change et les paramètres liés au tarif
 * d'un adulte ({@link #SINGLE_ADULT_FARE_PARAMS}) sont retirés pour qu'Aviasales relance la
 * recherche au lieu d'ouvrir un tarif qui ne vaut que pour une personne.
 */
@Service
public class FlightClickService {

    private static final Logger log = LoggerFactory.getLogger(FlightClickService.class);
    private static final Pattern SAFE_PATH = Pattern.compile("^/search/[A-Za-z0-9/?&=%._~+\\-|]*$");
    /** Chemin de recherche : départ, date, arrivée, date de retour facultative, classe, voyageurs. */
    private static final Pattern PASSENGERS_SUFFIX =
            Pattern.compile("^(/search/[A-Z]{3}\\d{4}[A-Z]{3}(?:\\d{4})?[a-z]?)\\d{1,3}$");
    /** Paramètres propres au tarif d'un adulte trouvé par le partenaire. */
    static final Set<String> SINGLE_ADULT_FARE_PARAMS = Set.of("t", "static_fare_key", "search_date");
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int SUB_ID_LENGTH = 12;

    /** Devises que le site aviasales.com applique réellement via {@code ?currency=}. */
    static final Set<String> SITE_CURRENCIES = Set.of("eur", "usd", "gbp", "cad");
    private static final String SITE_DEFAULT_CURRENCY = "eur";

    private final FlightClickRepository clicks;
    private final FlightUsers users;
    private final String bookingBaseUrl;
    private final String marker;
    private final boolean subIdEnabled;
    private final SecureRandom random = new SecureRandom();

    public FlightClickService(FlightClickRepository clicks, FlightUsers users,
                              @Value("${yadony.flights.travelpayouts.booking-base-url:https://www.aviasales.com}")
                              String bookingBaseUrl,
                              @Value("${yadony.flights.travelpayouts.marker:}") String marker,
                              @Value("${yadony.flights.travelpayouts.subid-enabled:false}") boolean subIdEnabled) {
        this.clicks = clicks;
        this.users = users;
        this.bookingBaseUrl = bookingBaseUrl.endsWith("/")
                ? bookingBaseUrl.substring(0, bookingBaseUrl.length() - 1) : bookingBaseUrl;
        this.marker = marker == null ? "" : marker.trim();
        this.subIdEnabled = subIdEnabled;
    }

    public FlightClickResponse click(String firebaseUid, FlightClickRequest request) {
        if (marker.isEmpty()) throw FlightErrors.unavailable();
        String path = request.offerPath().trim();
        if (!SAFE_PATH.matcher(path).matches() || path.contains("//") || path.contains("..")) {
            throw FlightErrors.invalid("flights-invalid-offer", "Offre de vol invalide.");
        }
        FlightUsers.Caller caller = users.resolve(firebaseUid);
        String subId = newSubId();

        FlightClickEntity click = new FlightClickEntity();
        click.setOwnerId(caller.id());
        click.setOriginCode(request.originCode().toUpperCase(Locale.ROOT));
        click.setDestinationCode(request.destinationCode().toUpperCase(Locale.ROOT));
        click.setPrice(request.price());
        click.setCurrency(request.currency() == null ? null : request.currency().toUpperCase(Locale.ROOT));
        click.setAirlineCode(request.airlineCode() == null ? null : request.airlineCode().toUpperCase(Locale.ROOT));
        click.setGate(request.gate());
        click.setSource(request.source());
        click.setSubId(subId);
        FlightPassengers passengers = request.passengers();
        if (passengers != null) click.setPassengers((short) passengers.total());
        try {
            clicks.save(click);
        } catch (RuntimeException e) {
            // Le clic est une statistique : l'utilisateur doit pouvoir réserver même si elle se perd.
            log.warn("Clic vol non enregistré : {}", e.getMessage());
        }
        return new FlightClickResponse(affiliateUrl(withPassengers(path, passengers), subId, request.currency()));
    }

    /**
     * Applique la composition du voyage au chemin. Sans composition, ou pour un adulte seul, le
     * chemin est rendu tel quel. Un chemin dont la forme n'est pas reconnue est aussi gardé : mieux
     * vaut ouvrir la recherche d'un adulte que perdre le lien.
     */
    static String withPassengers(String path, FlightPassengers passengers) {
        if (passengers == null || passengers.isSingleAdult()) return path;
        int q = path.indexOf('?');
        String route = q < 0 ? path : path.substring(0, q);
        Matcher m = PASSENGERS_SUFFIX.matcher(route);
        if (!m.matches()) {
            log.warn("Chemin de vol sans suffixe voyageurs reconnu, lien d'un adulte gardé : {}", route);
            return path;
        }
        String newRoute = m.group(1) + passengersSuffix(passengers);
        if (q < 0) return newRoute;
        StringBuilder query = new StringBuilder();
        for (String param : path.substring(q + 1).split("&")) {
            if (param.isEmpty()) continue;
            int eq = param.indexOf('=');
            String name = eq < 0 ? param : param.substring(0, eq);
            if (SINGLE_ADULT_FARE_PARAMS.contains(name)) continue;
            query.append(query.isEmpty() ? "" : "&").append(param);
        }
        return query.isEmpty() ? newRoute : newRoute + "?" + query;
    }

    /** Adultes, puis enfants et bébés ; les chiffres nuls de fin sont omis ({@code 2}, {@code 21}, {@code 201}). */
    static String passengersSuffix(FlightPassengers p) {
        if (p.infants() > 0) return "" + p.adults() + p.children() + p.infants();
        if (p.children() > 0) return "" + p.adults() + p.children();
        return String.valueOf(p.adults());
    }

    /**
     * Lien affilié : notre domaine + le chemin, avec notre {@code marker} et la {@code currency} du
     * site. Un {@code marker} ou une {@code currency} déjà présents dans le chemin sont remplacés,
     * jamais dupliqués (un marker étranger détournerait la commission). Le reste de la requête est
     * gardé tel quel, déjà encodé par le partenaire : le lien est construit sans réencodage.
     */
    String affiliateUrl(String path, String subId, String currency) {
        String markerValue = subIdEnabled ? marker + "." + subId : marker;
        return UriComponentsBuilder.fromUriString(bookingBaseUrl + path)
                .replaceQueryParam("marker", URLEncoder.encode(markerValue, StandardCharsets.UTF_8))
                .replaceQueryParam("currency", siteCurrency(currency))
                .build()
                .toUriString();
    }

    /** Devise de l'offre si le site la gère, sinon l'euro (devise par défaut de la recherche). */
    static String siteCurrency(String currency) {
        String c = currency == null ? "" : currency.trim().toLowerCase(Locale.ROOT);
        return SITE_CURRENCIES.contains(c) ? c : SITE_DEFAULT_CURRENCY;
    }

    private String newSubId() {
        char[] chars = new char[SUB_ID_LENGTH];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }
}
