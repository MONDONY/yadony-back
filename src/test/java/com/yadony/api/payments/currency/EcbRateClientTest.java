package com.yadony.api.payments.currency;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EcbRateClientTest {

    private final EcbRateClient client = new EcbRateClient("https://unused.test");

    /** Extrait réel du format eurofxref-daily : trois niveaux de Cube imbriqués. */
    private static final String SAMPLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
                             xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
              <gesmes:subject>Reference rates</gesmes:subject>
              <Cube>
                <Cube time="2026-08-31">
                  <Cube currency="USD" rate="1.1642"/>
                  <Cube currency="GBP" rate="0.86450"/>
                  <Cube currency="CHF" rate="0.9361"/>
                  <Cube currency="CAD" rate="1.6033"/>
                  <Cube currency="JPY" rate="171.35"/>
                </Cube>
              </Cube>
            </gesmes:Envelope>
            """;

    @Test
    void parse_extractsCurrencyRatePairs_ignoringStructuralCubes() {
        Map<String, BigDecimal> rates = client.parse(SAMPLE);

        assertThat(rates).containsEntry("USD", new BigDecimal("1.1642"))
                .containsEntry("CAD", new BigDecimal("1.6033"))
                .containsEntry("GBP", new BigDecimal("0.86450"))
                .containsEntry("CHF", new BigDecimal("0.9361"))
                // Le flux porte ~30 devises : celles hors catalogue restent dans la map,
                // c'est le service de synchronisation qui filtre par catalogue.
                .containsKey("JPY");
        assertThat(rates).hasSize(5);
    }

    @Test
    void parse_unreadableRate_isSkipped_notFatal() {
        String xml = SAMPLE.replace("rate=\"1.1642\"", "rate=\"n/a\"");

        Map<String, BigDecimal> rates = client.parse(xml);

        assertThat(rates).doesNotContainKey("USD");
        assertThat(rates).containsKey("CAD");
    }

    @Test
    void parse_garbage_returnsEmpty_neverThrows() {
        assertThat(client.parse("pas du xml")).isEmpty();
    }

    @Test
    void parse_doctype_isRejected_xxeGuard() {
        // Un DOCTYPE dans un flux externe = tentative XXE ou flux corrompu : rejet
        // silencieux, jamais de résolution d'entité.
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><Cube currency=\"USD\" rate=\"1.0\"/>";

        assertThat(client.parse(xml)).isEmpty();
    }
}
