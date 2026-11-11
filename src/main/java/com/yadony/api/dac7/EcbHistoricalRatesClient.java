package com.yadony.api.dac7;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Taux de référence historiques de la BCE ({@code eurofxref-hist.xml}, flux public sans clé) : une entrée par jour de
 * publication et par devise, en unités pour un euro. Appelé seulement par {@link EcbExchangeRateProvider#ensureCoverage}
 * (jamais pendant un calcul, jamais dans les tests).
 *
 * <p>Lecture en flux (StAX), DTD et entités externes refusées : le fichier vient d'internet.
 */
@Component
public class EcbHistoricalRatesClient {

    private static final Logger log = LoggerFactory.getLogger(EcbHistoricalRatesClient.class);

    private final RestClient restClient;
    private final Dac7EcbProperties properties;

    @Autowired
    public EcbHistoricalRatesClient(Dac7EcbProperties properties) {
        this(withTimeouts(), properties);
    }

    EcbHistoricalRatesClient(RestClient restClient, Dac7EcbProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /** Délais durs : un appel pendu ne doit jamais bloquer une page admin (même leçon que {@code EcbRateClient}). */
    private static RestClient withTimeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** Taux publiés depuis {@code from} inclus, par jour puis par devise ; vide si le flux est injoignable ou illisible. */
    public Map<LocalDate, Map<String, BigDecimal>> fetchSince(LocalDate from) {
        final byte[] body;
        try {
            body = restClient.get().uri(properties.histUrlOrDefault()).retrieve().body(byte[].class);
        } catch (RuntimeException e) {
            log.warn("Taux historiques BCE injoignables : {}", e.getClass().getSimpleName());
            return Map.of();
        }
        if (body == null || body.length == 0) {
            log.warn("Taux historiques BCE : réponse vide");
            return Map.of();
        }
        return parse(new ByteArrayInputStream(body), from);
    }

    static Map<LocalDate, Map<String, BigDecimal>> parse(InputStream xml, LocalDate from) {
        Map<LocalDate, Map<String, BigDecimal>> byDay = new TreeMap<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(xml);
            LocalDate day = null;
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT || !"Cube".equals(reader.getLocalName())) {
                    continue;
                }
                String time = reader.getAttributeValue(null, "time");
                if (time != null) {
                    day = parseDay(time);
                    continue;
                }
                String currency = reader.getAttributeValue(null, "currency");
                String rate = reader.getAttributeValue(null, "rate");
                if (day == null || currency == null || rate == null || day.isBefore(from)) {
                    continue;
                }
                String code = currency.strip().toUpperCase(Locale.ROOT);
                if (!code.matches("[A-Z]{3}")) {
                    continue;
                }
                try {
                    BigDecimal value = new BigDecimal(rate.strip());
                    if (value.signum() > 0) {
                        byDay.computeIfAbsent(day, d -> new HashMap<>()).put(code, value);
                    }
                } catch (NumberFormatException e) {
                    log.warn("Taux BCE illisible ignoré ({} le {})", code, day);
                }
            }
        } catch (XMLStreamException e) {
            log.warn("Taux historiques BCE illisibles : {}", e.getClass().getSimpleName());
            return Map.of();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // Rien à libérer de plus.
                }
            }
        }
        return byDay;
    }

    private static LocalDate parseDay(String raw) {
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
