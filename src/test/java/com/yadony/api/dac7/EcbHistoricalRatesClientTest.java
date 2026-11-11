package com.yadony.api.dac7;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Taux historiques BCE : lecture du flux eurofxref-hist")
class EcbHistoricalRatesClientTest {

    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
                             xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
              <gesmes:subject>Reference rates</gesmes:subject>
              <Cube>
                <Cube time="2026-03-06">
                  <Cube currency="USD" rate="1.0905"/>
                  <Cube currency="gbp" rate="0.8412"/>
                  <Cube currency="CAD" rate="n/a"/>
                  <Cube currency="CHF" rate="0"/>
                </Cube>
                <Cube time="2025-12-31">
                  <Cube currency="USD" rate="1.0400"/>
                </Cube>
              </Cube>
            </gesmes:Envelope>
            """;

    private static Map<LocalDate, Map<String, BigDecimal>> parse(String xml, String from) {
        return EcbHistoricalRatesClient.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                LocalDate.parse(from));
    }

    @Test
    @DisplayName("garde les jours à partir de la date demandée, codes en majuscules, ignore les taux illisibles ou nuls")
    void parse() {
        Map<LocalDate, Map<String, BigDecimal>> rates = parse(XML, "2026-01-01");
        assertThat(rates).containsOnlyKeys(LocalDate.parse("2026-03-06"));
        assertThat(rates.get(LocalDate.parse("2026-03-06")))
                .containsOnlyKeys("USD", "GBP")
                .containsEntry("USD", new BigDecimal("1.0905"));
        assertThat(parse(XML, "2025-01-01")).hasSize(2);
    }

    @Test
    @DisplayName("réponse bornée à 10 Mo : au-delà, abandonnée sans tout charger")
    void reponseBornee() throws Exception {
        byte[] small = EcbHistoricalRatesClient.readBounded(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertThat(small).containsExactly(1, 2, 3);
        java.io.InputStream endless = new java.io.InputStream() {
            @Override
            public int read() {
                return 'x';
            }
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EcbHistoricalRatesClient.readBounded(endless))
                .isInstanceOf(EcbHistoricalRatesClient.ResponseTooLargeException.class);
        assertThat(EcbHistoricalRatesClient.readBounded(null)).isEmpty();
    }

    @Test
    @DisplayName("flux illisible : vide, jamais d'exception")
    void illisible() {
        assertThat(parse("<Cube><Cube time=", "2026-01-01")).isEmpty();
    }

    @Test
    @DisplayName("entité externe refusée (XXE) : le fichier local n'est jamais lu")
    void xxe(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "1.2345");
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>"
                + "<Cube><Cube time=\"2026-03-06\"><Cube currency=\"USD\" rate=\"&xxe;\"/></Cube></Cube>";
        Map<LocalDate, Map<String, BigDecimal>> rates = parse(xml, "2026-01-01");
        assertThat(rates.values()).noneMatch(m -> m.containsValue(new BigDecimal("1.2345")));
    }
}
