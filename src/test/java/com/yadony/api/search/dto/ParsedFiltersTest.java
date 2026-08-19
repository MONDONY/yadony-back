package com.yadony.api.search.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedFiltersTest {

    @Test
    void accessors_returnTheConstructedValues() {
        ParsedFilters filters = new ParsedFilters(
                "Paris", "Bamako",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                BigDecimal.valueOf(20), BigDecimal.valueOf(30), BigDecimal.valueOf(8),
                BigDecimal.valueOf(4.5), true, false, null, null, "air", "documents");

        assertThat(filters.departureCity()).isEqualTo("Paris");
        assertThat(filters.arrivalCity()).isEqualTo("Bamako");
        assertThat(filters.minAvailableKg()).isEqualByComparingTo("20");
        assertThat(filters.maxPricePerKg()).isEqualByComparingTo("8");
        assertThat(filters.weekendOnly()).isTrue();
        assertThat(filters.urgent()).isFalse();
        assertThat(filters.kiloProOnly()).isNull();
    }

    @Test
    void serialization_omitsNullFields() throws Exception {
        // Seuls les champs effectivement reconnus doivent apparaître dans la réponse :
        // le client s'appuie sur leur absence pour savoir qu'aucune valeur n'a été comprise.
        ParsedFilters filters = new ParsedFilters(
                null, "Bamako", null, null, null, null, null, null, null, null, null, null, null, null);

        String json = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(filters);

        assertThat(json).contains("\"arrivalCity\":\"Bamako\"");
        assertThat(json).doesNotContain("departureCity")
                .doesNotContain("departureDateFrom")
                .doesNotContain("minAvailableKg");
    }
}
