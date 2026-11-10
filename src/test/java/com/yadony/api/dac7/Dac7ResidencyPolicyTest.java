package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Dac7ResidencyPolicyTest {

    private final Dac7ResidencyPolicy defaults = new Dac7ResidencyPolicy(new Dac7Properties(false, null));

    @Test
    void lUnionCompteVingtSeptEtatsDontLaFrance() {
        assertThat(Dac7ResidencyPolicy.EU_MEMBER_STATES).hasSize(27).contains("FR", "DE", "BE", "GR", "HR", "IE");
        assertThat(Dac7ResidencyPolicy.EU_MEMBER_STATES).doesNotContain("GB", "CH", "NO", "EL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FR", "fr", " de ", "BE", "PT", "SE"})
    void residentDeLUnion_declarable(String country) {
        assertThat(defaults.statusOf(country)).isEqualTo(Dac7ResidencyStatus.DECLARABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CI", "SN", "ML", "CM", "GB", "US", "CA", "CH"})
    void horsUnion_nonDeclarableTantQueLaListeDesPartenairesEstVide(String country) {
        assertThat(defaults.statusOf(country)).isEqualTo(Dac7ResidencyStatus.NON_DECLARABLE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "ZZ", "FRA", "F", "1A"})
    void paysAbsentOuInvalide_residenceInconnue(String country) {
        assertThat(defaults.statusOf(country)).isEqualTo(Dac7ResidencyStatus.RESIDENCE_INCONNUE);
    }

    @Test
    void etatPartenaireConfigure_declarable() {
        Dac7ResidencyPolicy policy = new Dac7ResidencyPolicy(new Dac7Properties(false, List.of("ci", " SN ")));
        assertThat(policy.statusOf("CI")).isEqualTo(Dac7ResidencyStatus.DECLARABLE);
        assertThat(policy.statusOf("SN")).isEqualTo(Dac7ResidencyStatus.DECLARABLE);
        assertThat(policy.statusOf("ML")).isEqualTo(Dac7ResidencyStatus.NON_DECLARABLE);
    }

    @Test
    void codePartenaireInvalide_dac7Allume_refuseAuDemarrage() {
        assertThatThrownBy(() -> new Dac7ResidencyPolicy(new Dac7Properties(true, List.of("CIV"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CIV");
    }

    @Test
    void codePartenaireInvalide_dac7Eteint_ignoreSansEmpecherLeDemarrage() {
        Dac7ResidencyPolicy policy = new Dac7ResidencyPolicy(new Dac7Properties(false, List.of("CIV", "SN")));
        assertThat(policy.invalidPartnerJurisdictions()).containsExactly("CIV");
        assertThat(policy.statusOf("SN")).isEqualTo(Dac7ResidencyStatus.DECLARABLE);
        assertThat(defaults.invalidPartnerJurisdictions()).isEmpty();
    }

    @Test
    void listeExacte_unionEtTerritoires() {
        assertThat(Dac7ResidencyPolicy.EU_MEMBER_STATES).containsExactlyInAnyOrder(
                "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE",
                "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE");
        assertThat(Dac7ResidencyPolicy.EU_TERRITORIES)
                .containsExactlyInAnyOrder("GP", "MQ", "GF", "RE", "YT", "MF", "AX");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GP", "MQ", "GF", "RE", "YT", "MF", "AX", "re"})
    void territoiresDeLUnion_declarables(String country) {
        assertThat(defaults.statusOf(country)).isEqualTo(Dac7ResidencyStatus.DECLARABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BL", "PF", "NC", "PM", "WF", "GL", "FO"})
    void paysEtTerritoiresHorsUnion_nonDeclarables(String country) {
        assertThat(defaults.statusOf(country)).isEqualTo(Dac7ResidencyStatus.NON_DECLARABLE);
    }

    @Test
    void conservation_defautDeuxAnsEtMinimumUn() {
        assertThat(new Dac7Properties(false, null).retentionYearsOrDefault()).isEqualTo(2);
        assertThat(new Dac7Properties(false, null, 0).retentionYearsOrDefault()).isEqualTo(2);
        assertThat(new Dac7Properties(false, null, 3).retentionYearsOrDefault()).isEqualTo(3);
    }

    @Test
    void particulier_paysDuProfil() {
        UserEntity user = new UserEntity();
        user.setCountry("FR");
        user.setTravelerStatus(TravelerStatus.INDIVIDUAL);
        assertThat(Dac7ResidencyPolicy.residenceCountryOf(user)).isEqualTo("FR");
        assertThat(defaults.statusOf(user)).isEqualTo(Dac7ResidencyStatus.DECLARABLE);
    }

    @Test
    void professionnel_paysDeLEntreprise() {
        UserEntity user = new UserEntity();
        user.setCountry("FR");
        user.becomeProfessional("Transports Diallo", "123456789", "SN", "2026-11-10");
        assertThat(Dac7ResidencyPolicy.residenceCountryOf(user)).isEqualTo("SN");
        assertThat(defaults.statusOf(user)).isEqualTo(Dac7ResidencyStatus.NON_DECLARABLE);
    }

    @Test
    void professionnelSansPaysDEntreprise_paysDuProfil() {
        assertThat(Dac7ResidencyPolicy.residenceCountryOf("BE", TravelerStatus.PROFESSIONAL, null)).isEqualTo("BE");
    }

    @Test
    void statutNonDeclare_paysDuProfil() {
        UserEntity user = new UserEntity();
        assertThat(defaults.statusOf(user)).isEqualTo(Dac7ResidencyStatus.RESIDENCE_INCONNUE);
        user.setCountry("de");
        assertThat(Dac7ResidencyPolicy.residenceCountryOf(user)).isEqualTo("DE");
    }
}
