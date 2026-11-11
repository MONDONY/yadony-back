package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Statut de collecte DAC7 (spec du 11/11/2026, § 2.2 et 2.3 ; tests 1, 2, 3 et 5 du § 6). */
class Dac7CollectionPolicyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate BIRTH = LocalDate.of(1990, 4, 12);
    private static final Dac7Address LYON = new Dac7Address("1 rue de la République", null, "69001", "Lyon", "FR",
            null);

    private final Dac7VerifiedIdentityPort port = mock(Dac7VerifiedIdentityPort.class);
    private final Dac7CollectionPolicy policy = new Dac7CollectionPolicy(
            new Dac7ResidencyPolicy(new Dac7Properties(true, null)), port, CLOCK);
    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(port.verifiedIdentity(any())).thenReturn(Dac7VerifiedIdentityPort.Lookup.none());
    }

    private void didit(String first, String last, LocalDate birth, Dac7Address address) {
        when(port.verifiedIdentity(user.getId())).thenReturn(Dac7VerifiedIdentityPort.Lookup.found(
                new Dac7VerifiedIdentity(first, last, birth, address, "session-42")));
    }

    @Test
    @DisplayName("test 1 : résident de Côte d'Ivoire → NE_PAS_SOLLICITER, rien à demander, aucune lecture Didit")
    void residentHorsUnion_neJamaisSolliciter() {
        user.setCountry("CI");

        Dac7Collection c = policy.evaluate(user);

        assertThat(c.status()).isEqualTo(Dac7CollectionStatus.NE_PAS_SOLLICITER);
        assertThat(c.status().actionRequired()).isFalse();
        assertThat(c.missing()).isEmpty();
        assertThat(c.identityKnown()).isFalse();
        verify(port, never()).verifiedIdentity(any());
    }

    @Test
    @DisplayName("test 2 : pays inconnu → seul le pays est demandé")
    void paysInconnu_seulLePays() {
        user.setCountry(null);
        assertThat(policy.evaluate(user).missing()).containsExactly(Dac7CollectionField.RESIDENCE_COUNTRY);
        assertThat(policy.evaluate(user).status()).isEqualTo(Dac7CollectionStatus.RESIDENCE_INCONNUE);
        user.setCountry("XX");
        assertThat(policy.evaluate(user).status()).isEqualTo(Dac7CollectionStatus.RESIDENCE_INCONNUE);
        verify(port, never()).verifiedIdentity(any());
    }

    @Test
    @DisplayName("test 3 : résident en France, pièce Didit complète → seul le NIF manque, champs Didit en lecture seule")
    void franceDiditComplet_seulLeNif() {
        user.setCountry("FR");
        didit("Awa", "Diallo", BIRTH, LYON);

        Dac7Collection c = policy.evaluate(user);

        assertThat(c.status()).isEqualTo(Dac7CollectionStatus.INFO_REQUIRED);
        assertThat(c.missing()).containsExactly(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER);
        assertThat(c.firstName()).isEqualTo("Awa");
        assertThat(c.birthDate()).isEqualTo(BIRTH);
        assertThat(c.address().city()).isEqualTo("Lyon");
        assertThat(c.readOnlyFields()).containsExactly(Dac7CollectionField.NAME, Dac7CollectionField.BIRTH_DATE,
                Dac7CollectionField.ADDRESS);
        assertThat(c.verificationRef()).isEqualTo("session-42");
        assertThat(c.toString()).doesNotContain("Awa").doesNotContain("Lyon").doesNotContain("session-42");
    }

    @Test
    @DisplayName("test 5 : résident de l'Union sans NIF → INFO_REQUIRED (jamais bloquant), liste exacte des manques")
    void residentSansRien_toutManque() {
        user.setCountry("BE");

        Dac7Collection c = policy.evaluate(user);

        assertThat(c.status()).isEqualTo(Dac7CollectionStatus.INFO_REQUIRED);
        assertThat(c.status().actionRequired()).isTrue();
        assertThat(c.missing()).containsExactly(Dac7CollectionField.NAME, Dac7CollectionField.BIRTH_DATE,
                Dac7CollectionField.ADDRESS, Dac7CollectionField.TAX_IDENTIFICATION_NUMBER);
        assertThat(c.readOnlyFields()).isEmpty();
    }

    @Test
    @DisplayName("carte d'identité sans adresse : nom et naissance repris de Didit, adresse à saisir")
    void pieceSansAdresse_adresseASaisir() {
        user.setCountry("FR");
        user.setTaxIdentificationNumber("1234567890123");
        user.setTaxCountry("FR");
        didit("Awa", "Diallo", BIRTH, null);

        Dac7Collection c = policy.evaluate(user);

        assertThat(c.missing()).containsExactly(Dac7CollectionField.ADDRESS);
        assertThat(c.readOnlyFields()).containsExactly(Dac7CollectionField.NAME, Dac7CollectionField.BIRTH_DATE);
    }

    @Test
    @DisplayName("tout présent (Didit + profil + NIF) → COMPLETE ; l'adresse du profil prime sur celle de la pièce")
    void complet() {
        user.setCountry("FR");
        user.setTaxIdentificationNumber("1234567890123");
        user.setTaxCountry("FR");
        user.setResidenceStreet("2 rue Neuve");
        user.setResidencePostalCode("75002");
        user.setCity("Paris");
        didit("Awa", "Diallo", BIRTH, LYON);

        Dac7Collection c = policy.evaluate(user);

        assertThat(c.status()).isEqualTo(Dac7CollectionStatus.COMPLETE);
        assertThat(c.missing()).isEmpty();
        assertThat(c.addressSource()).isEqualTo(Dac7FieldSource.PROFILE);
        assertThat(c.address().city()).isEqualTo("Paris");
        assertThat(c.address().countryCode()).isEqualTo("FR");
        assertThat(c.readOnlyFields()).containsExactly(Dac7CollectionField.NAME, Dac7CollectionField.BIRTH_DATE);
    }

    @Test
    @DisplayName("sans pièce : profil (nom, naissance, adresse complète) repris et modifiable")
    void profilSeul() {
        user.setCountry("DE");
        user.setFirstName("Jan");
        user.setLastName("Muller");
        user.setBirthDate(BIRTH);
        user.setResidenceStreet("Hauptstr. 1");
        user.setResidencePostalCode("10115");
        user.setCity("Berlin");
        user.setTaxIdentificationNumber("12345678901");
        user.setTaxCountry("DE");

        Dac7Collection c = policy.evaluate(user);

        assertThat(c.status()).isEqualTo(Dac7CollectionStatus.COMPLETE);
        assertThat(c.nameSource()).isEqualTo(Dac7FieldSource.PROFILE);
        assertThat(c.birthDateSource()).isEqualTo(Dac7FieldSource.PROFILE);
        assertThat(c.readOnlyFields()).isEmpty();
        assertThat(c.verificationRef()).isNull();
    }

    @Test
    @DisplayName("adresse incomplète (sans code postal) ou adresse de la pièce dans un autre pays : à saisir")
    void adresseIncompleteOuAutrePays() {
        user.setCountry("FR");
        user.setResidenceStreet("2 rue Neuve");
        user.setCity("Paris");
        didit("Awa", "Diallo", BIRTH, new Dac7Address("Rue 12", null, "BP 1", "Abidjan", "CI", null));
        assertThat(policy.evaluate(user).missing()).contains(Dac7CollectionField.ADDRESS);

        // Adresse en une ligne seulement : pas « complète » au sens de la spec (rue, code postal, ville).
        didit("Awa", "Diallo", BIRTH, new Dac7Address(null, null, null, null, "FR", "1 rue X 69001 Lyon"));
        assertThat(policy.evaluate(user).missing()).contains(Dac7CollectionField.ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456789012", "12345678901234", "12345678901AB"})
    @DisplayName("NIF français enregistré invalide (pas 13 chiffres) : toujours manquant")
    void nifFrancaisInvalide_manquant(String nif) {
        user.setCountry("FR");
        user.setTaxIdentificationNumber(nif);
        user.setTaxCountry("FR");
        assertThat(policy.evaluate(user).missing()).contains(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER);
    }

    @Test
    @DisplayName("NIF sans pays d'émission : manquant")
    void nifSansPays_manquant() {
        user.setCountry("FR");
        user.setTaxIdentificationNumber("1234567890123");
        assertThat(policy.evaluate(user).missing()).contains(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER);
    }

    @Test
    @DisplayName("lieu de naissance sans NIF : reste INFO_REQUIRED (pas de NOTIN, pas de complétude sans NIF)")
    void lieuDeNaissanceSansNif_resteIncomplet() {
        user.setCountry("FR");
        user.setTaxBirthPlace("Dakar");
        didit("Awa", "Diallo", BIRTH, LYON);
        Dac7Collection c = policy.evaluate(user);
        assertThat(c.status()).isEqualTo(Dac7CollectionStatus.INFO_REQUIRED);
        assertThat(c.missing()).containsExactly(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER);
    }

    @Test
    @DisplayName("prestataire injoignable : profil seul, signalé, jamais d'exception")
    void diditIndisponible() {
        user.setCountry("FR");
        user.setFirstName("Awa");
        user.setLastName("Diallo");
        when(port.verifiedIdentity(user.getId())).thenReturn(Dac7VerifiedIdentityPort.Lookup.unavailable());

        Dac7Collection c = policy.evaluate(user);

        assertThat(c.identityUnavailable()).isTrue();
        assertThat(c.nameSource()).isEqualTo(Dac7FieldSource.PROFILE);
        assertThat(c.missing()).contains(Dac7CollectionField.BIRTH_DATE);
        when(port.verifiedIdentity(user.getId())).thenReturn(null);
        assertThat(policy.evaluate(user).identityUnavailable()).isTrue();
    }

    @Test
    @DisplayName("professionnel : seul le NIF est collecté ici (modèle entité séparé, spec § 2.3)")
    void professionnel_seulLeNif() {
        user.setCountry("FR");
        user.setTravelerStatus(TravelerStatus.PROFESSIONAL);
        user.setBusinessCountry("FR");

        Dac7Collection c = policy.evaluate(user);

        assertThat(c.status()).isEqualTo(Dac7CollectionStatus.INFO_REQUIRED);
        assertThat(c.missing()).containsExactly(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER);
        assertThat(c.identityKnown()).isFalse();
        verify(port, never()).verifiedIdentity(any());

        user.setTaxIdentificationNumber("1234567890123");
        user.setTaxCountry("FR");
        assertThat(policy.evaluate(user).status()).isEqualTo(Dac7CollectionStatus.COMPLETE);
    }

    @Test
    @DisplayName("territoire de l'Union (La Réunion) : transmis comme la France, donc collecté")
    void territoire() {
        user.setCountry("RE");
        assertThat(policy.evaluate(user).status()).isEqualTo(Dac7CollectionStatus.INFO_REQUIRED);
    }

    @Test
    @DisplayName("lecture sans la pièce : aucun appel au prestataire")
    void sansPiece() {
        user.setCountry("FR");
        didit("Awa", "Diallo", BIRTH, LYON);
        Dac7Collection c = policy.evaluate(user, Dac7VerifiedIdentityPort.Lookup.none());
        assertThat(c.missing()).contains(Dac7CollectionField.NAME);
        verify(port, never()).verifiedIdentity(any());
    }

    @Test
    @DisplayName("NIF : 13 chiffres exactement pour la France, non vide ailleurs")
    void reglesNif() {
        assertThat(Dac7CollectionPolicy.validTaxIdentifier("1234567890123", "FR")).isTrue();
        assertThat(Dac7CollectionPolicy.validTaxIdentifier("123456789012", "FR")).isFalse();
        assertThat(Dac7CollectionPolicy.validTaxIdentifier("ABC1", "DE")).isTrue();
        assertThat(Dac7CollectionPolicy.validTaxIdentifier(" ", "DE")).isFalse();
        assertThat(Dac7CollectionPolicy.validTaxIdentifier(null, "DE")).isFalse();
        assertThat(Dac7CollectionPolicy.validTaxIdentifier("1234", null)).isFalse();
    }
}
