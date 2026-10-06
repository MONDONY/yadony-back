package com.yadony.api.admin.export;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    @Test
    void neutraliseLesFormulesMaisPasLesNombresNegatifs() {
        assertThat(CsvWriter.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(CsvWriter.cell("+33 6")).isEqualTo("'+33 6");
        assertThat(CsvWriter.cell("@moussa")).isEqualTo("'@moussa");
        assertThat(CsvWriter.cell("-cmd")).isEqualTo("'-cmd");
        assertThat(CsvWriter.cell("-12.50")).isEqualTo("-12.50");
        assertThat(CsvWriter.cell("Paris → Dakar")).isEqualTo("Paris → Dakar");
    }

    @Test
    void guillemetsVirgulesEtValeursAbsentes() {
        assertThat(CsvWriter.cell("Diallo, Awa")).isEqualTo("\"Diallo, Awa\"");
        assertThat(CsvWriter.cell("ligne\nsuivante")).isEqualTo("\"ligne\nsuivante\"");
        assertThat(CsvWriter.cell(null)).isEmpty();
        assertThat(CsvWriter.cell("")).isEmpty();
    }

    @Test
    void bomEnTeteEtUneLigneParAppel() {
        byte[] bytes = new CsvWriter("a,b").row("1", null).row("x,y", "z").bytes();

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("﻿a,b\n1,\n\"x,y\",z\n");
    }
}
