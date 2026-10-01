package com.yadony.api.addressbook.invitation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationTargetsTest {

    @Test
    void phone_acceptsStrictE164WithSpaces() {
        assertThat(InvitationTargets.phone("+221 77 123 45 12")).contains("+221771234512");
        assertThat(InvitationTargets.phone("+33 6-12.34(56)78")).contains("+33612345678");
    }

    @Test
    void phone_rejectsNationalOrMalformedNumbers() {
        assertThat(InvitationTargets.phone(null)).isEmpty();
        assertThat(InvitationTargets.phone("771234512")).isEmpty();
        assertThat(InvitationTargets.phone("00221771234512")).isEmpty();
        assertThat(InvitationTargets.phone("+0221771234512")).isEmpty();
        assertThat(InvitationTargets.phone("+22177")).isEmpty();
        assertThat(InvitationTargets.phone("+2217712345123456")).isEmpty();
    }

    @Test
    void email_isTrimmedAndLowercased() {
        assertThat(InvitationTargets.email("  Awa.Diallo@Gmail.COM ")).contains("awa.diallo@gmail.com");
        assertThat(InvitationTargets.email(null)).isEmpty();
        assertThat(InvitationTargets.email("awa@gmail")).isEmpty();
        assertThat(InvitationTargets.email("awa diallo@gmail.com")).isEmpty();
        assertThat(InvitationTargets.email("a".repeat(250) + "@x.com")).isEmpty();
    }

    @Test
    void hash_isSha256HexOfNormalizedValue() {
        assertThat(InvitationTargets.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(InvitationTargets.hash("+221771234512")).hasSize(64).doesNotContain("221771234512");
    }

    @Test
    void maskPhone_keepsCallingCodeAndLastTwoDigits() {
        assertThat(InvitationTargets.maskPhone("+221771234512")).isEqualTo("+221 •• •• •• 12");
        assertThat(InvitationTargets.maskPhone("+33612345678")).isEqualTo("+33 •• •• •• 78");
        assertThat(InvitationTargets.maskPhone("+15145550123")).isEqualTo("+1 •• •• •• 23");
        assertThat(InvitationTargets.maskPhone("+79161234567")).isEqualTo("+7 •• •• •• 67");
    }

    @Test
    void maskEmail_keepsFirstLetterAndDomain() {
        assertThat(InvitationTargets.maskEmail("awa.diallo@gmail.com")).isEqualTo("a••••@gmail.com");
        assertThat(InvitationTargets.maskEmail("b@yadony.com")).isEqualTo("b••••@yadony.com");
    }

    @Test
    void countryOf_coversCorridorsAndDiaspora() {
        assertThat(InvitationTargets.countryOf("+221771234512")).contains("SN");
        assertThat(InvitationTargets.countryOf("+2250701020304")).contains("CI");
        assertThat(InvitationTargets.countryOf("+22376123456")).contains("ML");
        assertThat(InvitationTargets.countryOf("+237612345678")).contains("CM");
        assertThat(InvitationTargets.countryOf("+33612345678")).contains("FR");
        assertThat(InvitationTargets.countryOf("+393331234567")).contains("IT");
        assertThat(InvitationTargets.countryOf("+15145550123")).contains("US");
        assertThat(InvitationTargets.countryOf("+243812345678")).contains("CD");
        assertThat(InvitationTargets.countryOf("+212612345678")).contains("MA");
        assertThat(InvitationTargets.countryOf("+4915112345678")).contains("DE");
    }

    @Test
    void countryOf_unknownCallingCode_isEmpty() {
        assertThat(InvitationTargets.countryOf("+81312345678")).isEmpty();
        assertThat(InvitationTargets.countryOf("+79161234567")).isEmpty();
        assertThat(InvitationTargets.countryOf("+260971234567")).isEmpty();
    }
}
