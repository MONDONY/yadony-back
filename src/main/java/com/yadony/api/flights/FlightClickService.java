package com.yadony.api.flights;

import com.yadony.api.flights.dto.FlightClickRequest;
import com.yadony.api.flights.dto.FlightClickResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Transforme l'offre choisie en lien affilié et enregistre le clic.
 *
 * <p>Le domaine du lien est le nôtre (configuration), jamais celui envoyé par l'app :
 * {@code offerPath} n'est accepté que s'il ressemble à un chemin de recherche du
 * partenaire, ce qui ferme toute redirection vers un autre site.
 *
 * <p>Sous-identifiant : quand {@code subid-enabled} est vrai, le marker devient
 * {@code <marker>.<subId>} pour retrouver le clic dans les statistiques Travelpayouts.
 * Désactivé par défaut tant que le format n'est pas validé sur le compte partenaire :
 * un marker mal formé ferait perdre la commission.
 */
@Service
public class FlightClickService {

    private static final Logger log = LoggerFactory.getLogger(FlightClickService.class);
    private static final Pattern SAFE_PATH = Pattern.compile("^/search/[A-Za-z0-9/?&=%._~+\\-|]*$");
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int SUB_ID_LENGTH = 12;

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
        try {
            clicks.save(click);
        } catch (RuntimeException e) {
            // Le clic est une statistique : l'utilisateur doit pouvoir réserver même si elle se perd.
            log.warn("Clic vol non enregistré : {}", e.getMessage());
        }
        return new FlightClickResponse(affiliateUrl(path, subId));
    }

    String affiliateUrl(String path, String subId) {
        String markerValue = subIdEnabled ? marker + "." + subId : marker;
        String separator = path.contains("?") ? "&" : "?";
        return bookingBaseUrl + path + separator + "marker=" + URLEncoder.encode(markerValue, StandardCharsets.UTF_8);
    }

    private String newSubId() {
        char[] chars = new char[SUB_ID_LENGTH];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }
}
