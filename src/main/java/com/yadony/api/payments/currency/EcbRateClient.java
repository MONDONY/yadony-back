package com.yadony.api.payments.currency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lit les taux de référence quotidiens de la BCE ({@code eurofxref-daily.xml}) :
 * flux public, sans clé, publié chaque jour ouvré vers 16 h CET. Une entrée par
 * devise, en unités pour un euro — exactement la sémantique de
 * {@code exchange_rates.units_per_eur}.
 *
 * <p>Ne renvoie que ce que le flux contient : XOF/XAF n'y figurent pas (parité fixe
 * par traité, hors marché), et c'est très bien — la synchronisation ne doit de toute
 * façon jamais y toucher.
 */
@Component
public class EcbRateClient {

    private static final Logger log = LoggerFactory.getLogger(EcbRateClient.class);

    private final RestClient restClient;
    private final String url;

    @org.springframework.beans.factory.annotation.Autowired
    public EcbRateClient(
            @Value("${yadony.exchange-rates.ecb-url:https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml}")
            String url) {
        this(RestClient.create(), url);
    }

    EcbRateClient(RestClient restClient, String url) {
        this.restClient = restClient;
        this.url = url;
    }

    /**
     * Taux du jour par code devise (majuscules), vide si le flux est injoignable ou
     * illisible — l'appelant garde alors les taux de la veille, ce qui est toujours
     * un état correct.
     */
    public Map<String, BigDecimal> fetchDailyRates() {
        final String body;
        try {
            body = restClient.get().uri(url).retrieve().body(String.class);
        } catch (RuntimeException e) {
            log.warn("Flux BCE injoignable ({}) : {}", url, e.getMessage());
            return Map.of();
        }
        if (body == null || body.isBlank()) {
            log.warn("Flux BCE vide ({})", url);
            return Map.of();
        }
        return parse(body);
    }

    Map<String, BigDecimal> parse(String xml) {
        Map<String, BigDecimal> rates = new HashMap<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Flux externe : neutraliser DTD et entités externes (XXE) avant tout parse.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList cubes = doc.getElementsByTagName("Cube");
            for (int i = 0; i < cubes.getLength(); i++) {
                Element cube = (Element) cubes.item(i);
                String currency = cube.getAttribute("currency");
                String rate = cube.getAttribute("rate");
                if (currency.isBlank() || rate.isBlank()) {
                    continue; // Cube racine et Cube daté n'ont pas ces attributs.
                }
                try {
                    rates.put(currency.trim().toUpperCase(Locale.ROOT), new BigDecimal(rate.trim()));
                } catch (NumberFormatException e) {
                    log.warn("Taux BCE illisible pour {} : « {} »", currency, rate);
                }
            }
        } catch (Exception e) {
            log.warn("Flux BCE non parsable : {}", e.getMessage());
            return Map.of();
        }
        return rates;
    }
}
