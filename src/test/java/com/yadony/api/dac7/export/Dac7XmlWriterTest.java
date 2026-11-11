package com.yadony.api.dac7.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("XML DPI-DAC7 : conforme au XSD de collecte de la DGFiP (revenus 2026)")
class Dac7XmlWriterTest {

    static Dac7XmlDocument.Operator operator() {
        return new Dac7XmlDocument.Operator("123456789", "Yadony SAS", "Yadony", null, "10 rue de la Paix", "75002",
                "Paris", "op0001");
    }

    static Dac7XmlSeller individual(String vat, Dac7XmlSeller.Address address) {
        return new Dac7XmlSeller(false, "FR", "1234567890123", "FR", vat, "Awa", "Diallo", LocalDate.of(1990, 1, 2),
                null, null, address, List.of(new Dac7XmlSeller.Quarter(95, 1, 5), new Dac7XmlSeller.Quarter(0, 0, 0),
                new Dac7XmlSeller.Quarter(1250, 3, 63), new Dac7XmlSeller.Quarter(10, 1, 1)));
    }

    static byte[] xml(Dac7XmlSeller... sellers) {
        List<Dac7XmlDocument.Seller> list = new java.util.ArrayList<>();
        for (int i = 0; i < sellers.length; i++) {
            list.add(new Dac7XmlDocument.Seller(sellers[i], "s" + i + "abc"));
        }
        return Dac7XmlWriter.write(new Dac7XmlDocument(2026, LocalDateTime.of(2027, 1, 15, 10, 0, 0), "20270115a1",
                operator(), list));
    }

    @Test
    @DisplayName("personne physique (adresse détaillée, sans TVA) et entité (adresse libre, TVA) : valides")
    void valideContreLeXsd() {
        Dac7XmlSeller person = individual(null,
                new Dac7XmlSeller.Address("FR", "1 rue de Paris", null, "69001", "Lyon", null));
        Dac7XmlSeller entity = new Dac7XmlSeller(true, "BE", "BE0123456789", "BE", "BE0123456789", null, null, null,
                "Diallo Express", "0123456789", new Dac7XmlSeller.Address("BE", null, null, null, null,
                "Rue Neuve 1, 1000 Bruxelles"), individual(null, null).quarters());

        byte[] xml = xml(person, entity);

        Dac7XsdValidator.validate(xml);
        String text = new String(xml, StandardCharsets.UTF_8);
        assertThat(text).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(Dac7XmlWriter.startsWithBom(xml)).isFalse();
        assertThat(text).contains("<dpi:MessageRefId>OP_2026_123456789_20270115a1</dpi:MessageRefId>")
                .contains("<dpi:ReportingPeriod>2026-12-31</dpi:ReportingPeriod>")
                .contains("<dpi:MessageTypeIndic>DPI401</dpi:MessageTypeIndic>")
                .contains("<dpi:TIN issuedBy=\"FR\">1234567890123</dpi:TIN>")
                .contains("<dpi:ConsQ3 currCode=\"EUR\">1250</dpi:ConsQ3>")
                .contains("<dpi:FeesQ3 currCode=\"EUR\">63</dpi:FeesQ3>")
                .contains("<dpi:NumbQ3>3</dpi:NumbQ3>")
                .contains("<dpi:TaxesQ1 currCode=\"EUR\">0</dpi:TaxesQ1>")
                .contains("<dpi:BirthDate>1990-01-02</dpi:BirthDate>")
                .contains("<stf:DocRefId>OP_2026_123456789_s0abc</stf:DocRefId>")
                .contains("<dpi:PersonalServices>");
        // Balise facultative absente plutôt que vide (CV02).
        assertThat(text).doesNotContain("<dpi:VAT></dpi:VAT>").doesNotContain("<dpi:Street></dpi:Street>");
    }

    @Test
    @DisplayName("caractères spéciaux échappés, document toujours valide")
    void echappement() {
        Dac7XmlSeller person = new Dac7XmlSeller(false, "FR", "AB-12/34", "FR", null, "Zoé & Co", "N'Diaye <Fall>",
                LocalDate.of(1980, 12, 31), null, null,
                new Dac7XmlSeller.Address("FR", "2 rue \"Haute\"", "Bât. B", null, "Saint-Étienne", null),
                individual(null, null).quarters());
        byte[] xml = xml(person);
        Dac7XsdValidator.validate(xml);
        assertThat(new String(xml, StandardCharsets.UTF_8)).contains("Zoé &amp; Co").contains("&lt;Fall>");
    }

    @Test
    @DisplayName("fichier non conforme : refusé avec sa seule position, jamais la valeur fautive")
    void nonConforme() {
        Dac7XmlSeller person = new Dac7XmlSeller(false, "FR", "SECRET-NIF-42", "XX", null, "Awa", "Diallo",
                LocalDate.of(1990, 1, 2), null, null,
                new Dac7XmlSeller.Address("FR", null, null, null, "Lyon", null), individual(null, null).quarters());
        assertThatThrownBy(() -> Dac7XsdValidator.validate(xml(person)))
                .isInstanceOf(Dac7XsdValidator.InvalidFileException.class)
                .hasMessageContaining("ligne")
                .hasMessageNotContaining("SECRET-NIF-42")
                .hasMessageNotContaining("XX");
        assertThatThrownBy(() -> Dac7XsdValidator.validate("pas du xml".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Dac7XsdValidator.InvalidFileException.class);
    }

    @Test
    @DisplayName("aucun vendeur : document valide pour le schéma (refusé plus tôt par l'export)")
    void sansVendeur() {
        Dac7XsdValidator.validate(xml());
    }
}
