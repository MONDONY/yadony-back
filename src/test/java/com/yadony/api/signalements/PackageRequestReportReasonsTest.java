package com.yadony.api.signalements;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PackageRequestReportReasonsTest {

    @ParameterizedTest
    @CsvSource({
            "PROHIBITED, PROHIBITED_ITEM",
            "SCAM, SCAM_ATTEMPT",
            "INAPPROPRIATE, INAPPROPRIATE_CONTENT",
            "OTHER, OTHER",
            "PROHIBITED_ITEM, PROHIBITED_ITEM",
            "SCAM_ATTEMPT, SCAM_ATTEMPT",
            "FALSE_INFORMATION, FALSE_INFORMATION",
            "INAPPROPRIATE_CONTENT, INAPPROPRIATE_CONTENT",
            "' scam ', SCAM_ATTEMPT"
    })
    void motifsConnus_convertisSansToucherALaDescription(String raw, ReportReason expected) {
        PackageRequestReportReasons.Converted c = PackageRequestReportReasons.convert(raw, "détails");

        assertThat(c.reason()).isEqualTo(expected);
        assertThat(c.description()).isEqualTo("détails");
        assertThat(expected.appliesTo(ReportTargetType.PACKAGE_REQUEST)).isTrue();
    }

    @Test
    void motifInconnu_devientOtherAvecLeMotifDOrigineEnTete() {
        assertThat(PackageRequestReportReasons.convert("Motif libre", "détails"))
                .isEqualTo(new PackageRequestReportReasons.Converted(ReportReason.OTHER,
                        "[Motif d'origine : Motif libre] détails"));
        assertThat(PackageRequestReportReasons.convert("HARASSMENT", null))
                .isEqualTo(new PackageRequestReportReasons.Converted(ReportReason.OTHER,
                        "[Motif d'origine : HARASSMENT]"));
        assertThat(PackageRequestReportReasons.convert("X", "   ").description())
                .isEqualTo("[Motif d'origine : X]");
    }

    @Test
    void motifNul_devientOtherSansPrefixe() {
        assertThat(PackageRequestReportReasons.convert(null, "d"))
                .isEqualTo(new PackageRequestReportReasons.Converted(ReportReason.OTHER, "d"));
    }
}
