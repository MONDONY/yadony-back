package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.dto.TaxInfoRequest;
import com.yadony.api.dac7.dto.TaxInfoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TravelerTaxInfoServiceTest {

    private static final String UID = "uid-tax";
    private static final String NIF = "1234567890123";
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private final UserRepository users = mock(UserRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final Dac7DueDiligenceService diligence = mock(Dac7DueDiligenceService.class);
    private final Dac7ResidencyPolicy policy = new Dac7ResidencyPolicy(new Dac7Properties(true, null));
    private final Dac7ReportReadModel readModel = mock(Dac7ReportReadModel.class);
    private final Dac7VerifiedIdentityPort port = mock(Dac7VerifiedIdentityPort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TravelerTaxInfoService service = new TravelerTaxInfoService(users, audit, diligence, policy,
            new Dac7SolicitationPolicy(policy, readModel), new Dac7CollectionPolicy(policy, new Dac7IdentityResolver(port), clock), clock,
            org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setFirebaseUid(UID);
        user.setCountry("FR");
        when(users.findByFirebaseUid(UID)).thenReturn(Optional.of(user));
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        when(port.verifiedIdentity(any())).thenReturn(Dac7VerifiedIdentityPort.Lookup.none());
    }

    @Test
    void get_rienFourni() {
        TaxInfoResponse r = service.get(UID);
        assertThat(r.provided()).isFalse();
        assertThat(r.maskedTaxIdentificationNumber()).isNull();
        assertThat(r.taxCountry()).isNull();
        assertThat(r.vatNumberProvided()).isFalse();
        assertThat(r.providedAt()).isNull();
        assertThat(r.residenceCountry()).isEqualTo("FR");
        assertThat(r.dac7Status()).isEqualTo(Dac7ResidencyStatus.DECLARABLE);
        // Jamais payé : aucune demande, même résident en France.
        assertThat(r.solicitation()).isEqualTo(Dac7SolicitationStatus.NE_PAS_SOLLICITER);
    }

    @Test
    void get_dejaPayeSansNif_solliciter() {
        when(readModel.hasBeenPaid(user.getId())).thenReturn(true);
        assertThat(service.get(UID).solicitation()).isEqualTo(Dac7SolicitationStatus.SOLLICITER);
    }

    @Test
    void put_puisGet_dejaFourni() {
        when(readModel.hasBeenPaid(user.getId())).thenReturn(true);
        assertThat(service.set(UID, new TaxInfoRequest(NIF, "FR", null)).solicitation())
                .isEqualTo(Dac7SolicitationStatus.DEJA_FOURNI);
        assertThat(service.get(UID).solicitation()).isEqualTo(Dac7SolicitationStatus.DEJA_FOURNI);
    }

    @Test
    void get_nifMasqueQuatreDerniersCaracteres() {
        user.setTaxIdentificationNumber(NIF);
        user.setTaxCountry("FR");
        user.setVatNumber("FR12345678901");
        user.setTaxInfoProvidedAt(NOW);

        TaxInfoResponse r = service.get(UID);

        assertThat(r.provided()).isTrue();
        assertThat(r.maskedTaxIdentificationNumber()).isEqualTo("****0123");
        assertThat(r.taxCountry()).isEqualTo("FR");
        assertThat(r.vatNumberProvided()).isTrue();
        assertThat(r.maskedVatNumber()).isEqualTo("****8901");
        assertThat(r.providedAt()).isEqualTo(NOW);
        assertThat(r.toString()).doesNotContain(NIF).doesNotContain("FR12345678901");
    }

    @Test
    void masque_jamaisPlusQueLaLongueurMoinsQuatre() {
        assertThat(TravelerTaxInfoService.mask(null)).isNull();
        assertThat(TravelerTaxInfoService.mask("ABCD")).isEqualTo("****");
        assertThat(TravelerTaxInfoService.mask("ABCDEF")).isEqualTo("****EF");
        assertThat(TravelerTaxInfoService.mask("ABCDEFGH")).isEqualTo("****EFGH");
        assertThat(TravelerTaxInfoService.mask("ABCDEFGHIJKL")).isEqualTo("****IJKL");
    }

    @Test
    void put_enregistreNormaliseAuditeSansValeurEtInscritAuRegistre() {
        // Pays d'émission hors France : règle générale (lettres, chiffres, séparateurs), valeur normalisée.
        TaxInfoResponse r = service.set(UID, new TaxInfoRequest("  12 345.678-90/1 ", " it ", " fr123 "));

        assertThat(user.getTaxIdentificationNumber()).isEqualTo("12 345.678-90/1");
        assertThat(user.getTaxCountry()).isEqualTo("IT");
        assertThat(user.getVatNumber()).isEqualTo("FR123");
        assertThat(user.getTaxInfoProvidedAt()).isEqualTo(NOW);
        assertThat(r.provided()).isTrue();
        assertThat(r.maskedTaxIdentificationNumber()).isEqualTo("****90/1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("USER"), eq(user.getId()), eq("TRAVELER_TAX_INFO_SET"), eq(user.getId()),
                payload.capture());
        assertThat(payload.getValue().toString()).doesNotContain("345").doesNotContain("FR123")
                .doesNotContain("IT");
        assertThat(payload.getValue()).containsEntry("fields", java.util.List.of("TAX_IDENTIFICATION_NUMBER",
                "VAT_NUMBER"));
        verify(diligence).recordProvidedByUser(user.getId(), "TAX_IDENTIFICATION_NUMBER,VAT_NUMBER");
    }

    @Test
    void put_memesValeursDeuxFois_idempotent() {
        service.set(UID, new TaxInfoRequest(NIF, "FR", null));
        Instant first = user.getTaxInfoProvidedAt();

        TaxInfoResponse second = service.set(UID, new TaxInfoRequest(" " + NIF + " ", "fr", "  "));

        assertThat(second.provided()).isTrue();
        assertThat(user.getTaxInfoProvidedAt()).isEqualTo(first);
        verify(users, times(1)).save(any());
        verify(audit, times(1)).log(anyString(), any(), anyString(), any(), anyMap());
        verify(diligence, times(1)).recordProvidedByUser(any(), any());
    }

    @Test
    void put_numeroDeTvaAjoute_nouvelleEcriture() {
        service.set(UID, new TaxInfoRequest(NIF, "FR", null));
        service.set(UID, new TaxInfoRequest(NIF, "FR", "FR12345678901"));

        assertThat(user.getVatNumber()).isEqualTo("FR12345678901");
        verify(audit, times(2)).log(anyString(), any(), eq("TRAVELER_TAX_INFO_SET"), any(), anyMap());
        verify(diligence, times(2)).recordProvidedByUser(eq(user.getId()), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "1234567890123456789012345678901234567890X", "12#45", "AB_CD", "-1234",
            "1234-", "ÉTÉ1234", "    "})
    void put_nifInvalide_422SansRecopierLaValeur(String nif) {
        assertThatThrownBy(() -> service.set(UID, new TaxInfoRequest(nif, "FR", null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(422);
                    assertThat(e.getErrorCode()).isEqualTo("tax-info-invalid");
                    if (!nif.isBlank()) assertThat(e.getMessage()).doesNotContain(nif.strip());
                });
        verify(users, never()).save(any());
        verify(audit, never()).log(anyString(), any(), anyString(), any(), anyMap());
    }

    @Test
    void put_corpsAbsent_422() {
        assertThatThrownBy(() -> service.set(UID, null))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("tax-info-invalid"));
        verify(users, never()).save(any());
    }

    @Test
    void put_nifNul_422() {
        assertThatThrownBy(() -> service.set(UID, new TaxInfoRequest(null, "FR", null)))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FRA", "F", "ZZ", "1A", ""})
    void put_paysInvalide_422(String country) {
        assertThatThrownBy(() -> service.set(UID, new TaxInfoRequest(NIF, country, null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("tax-info-invalid"));
        verify(users, never()).save(any());
    }

    @Test
    void put_tvaInvalide_422() {
        assertThatThrownBy(() -> service.set(UID, new TaxInfoRequest(NIF, "FR", "FR#1")))
                .isInstanceOf(YadonyBusinessException.class);
        verify(users, never()).save(any());
    }

    @Test
    void utilisateurInconnu_404() {
        when(users.findByFirebaseUid("absent")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("absent"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.set("absent", new TaxInfoRequest(NIF, "FR", null)))
                .isInstanceOf(YadonyBusinessException.class);
    }

    // ---- Collecte ciblée (spec du 11/11/2026) ----

    private static final java.time.LocalDate BIRTH = java.time.LocalDate.of(1990, 4, 12);

    private void didit(Dac7Address address) {
        when(port.verifiedIdentity(user.getId())).thenReturn(Dac7VerifiedIdentityPort.Lookup.found(
                new Dac7VerifiedIdentity("Awa", "Diallo", BIRTH, address, "session-42")));
    }

    private static TaxInfoRequest identity(String first, String last, String birth, String place,
                                           TaxInfoRequest.AddressInput address) {
        return new TaxInfoRequest(null, null, null, first, last, birth, place, address);
    }

    private static void assert422(Runnable call, String code, String field) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(422);
            assertThat(e.getErrorCode()).isEqualTo(code);
            assertThat(e.getProperties()).containsEntry("field", field);
        });
    }

    @Test
    void test4_nifFrancais_douzeChiffresRefuse_treizeAccepte() {
        assertThatThrownBy(() -> service.set(UID, new TaxInfoRequest("123456789012", "FR", null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(422);
                    assertThat(e.getErrorCode()).isEqualTo("tax-info-invalid");
                    assertThat(e.getMessage()).doesNotContain("123456789012");
                });
        assert422(() -> service.set(UID, new TaxInfoRequest("12345678901234", "FR", null)), "tax-info-invalid",
                "taxIdentificationNumber");
        assert422(() -> service.set(UID, new TaxInfoRequest("123456789012A", "FR", null)), "tax-info-invalid",
                "taxIdentificationNumber");
        verify(users, never()).save(any());

        TaxInfoResponse r = service.set(UID, new TaxInfoRequest("1234 5678 90123", "FR", null));

        assertThat(user.getTaxIdentificationNumber()).isEqualTo("1234567890123");
        assertThat(r.provided()).isTrue();
        assertThat(r.missingFields()).doesNotContain(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER);
    }

    @Test
    void nifAutrePays_nonVideSuffit() {
        service.set(UID, new TaxInfoRequest("AB12", "DE", null));
        assertThat(user.getTaxIdentificationNumber()).isEqualTo("AB12");
    }

    @Test
    void test1_residentCoteDIvoire_rienADemander_aucuneIdentite() {
        user.setCountry("CI");
        TaxInfoResponse r = service.get(UID);
        assertThat(r.collectionStatus()).isEqualTo(Dac7CollectionStatus.NE_PAS_SOLLICITER);
        assertThat(r.actionRequired()).isFalse();
        assertThat(r.missingFields()).isEmpty();
        assertThat(r.identity()).isNull();
        verify(port, never()).verifiedIdentity(any());
        // Rien d'identité n'est collecté hors de l'Union.
        assertThatThrownBy(() -> service.set(UID, identity(null, null, "1990-04-12", null, null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("tax-info-not-applicable"));
        verify(users, never()).save(any());
    }

    @Test
    void test2_paysInconnu_seulLePays() {
        user.setCountry(null);
        TaxInfoResponse r = service.get(UID);
        assertThat(r.collectionStatus()).isEqualTo(Dac7CollectionStatus.RESIDENCE_INCONNUE);
        assertThat(r.missingFields()).containsExactly(Dac7CollectionField.RESIDENCE_COUNTRY);
        assertThat(r.actionRequired()).isTrue();
        assertThat(r.identity()).isNull();
    }

    @Test
    void test3_franceDiditComplet_seulLeNif_champsEnLectureSeule() {
        didit(new Dac7Address("1 rue de la République", null, "69001", "Lyon", "FR", null));

        TaxInfoResponse r = service.get(UID);

        assertThat(r.collectionStatus()).isEqualTo(Dac7CollectionStatus.INFO_REQUIRED);
        assertThat(r.missingFields()).containsExactly(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER);
        assertThat(r.readOnlyFields()).containsExactly(Dac7CollectionField.NAME, Dac7CollectionField.BIRTH_DATE,
                Dac7CollectionField.ADDRESS);
        assertThat(r.identity().firstName()).isEqualTo("Awa");
        assertThat(r.identity().nameSource()).isEqualTo(Dac7FieldSource.DIDIT);
        assertThat(r.identity().birthDate()).isEqualTo(BIRTH);
        assertThat(r.identity().address().city()).isEqualTo("Lyon");
        assertThat(r.identity().address().country()).isEqualTo("FR");
        assertThat(r.identity().addressReplaceable()).isTrue();
        assertThat(r.identity().birthPlaceProvided()).isFalse();
        assertThat(r.identity().identityCheckUnavailable()).isNull();
        assertThat(r.identity().toString()).doesNotContain("Awa");

        // Le NIF complète : COMPLETE, preuve Didit inscrite au registre.
        TaxInfoResponse after = service.set(UID, new TaxInfoRequest("1234567890123", "FR", null));
        assertThat(after.collectionStatus()).isEqualTo(Dac7CollectionStatus.COMPLETE);
        assertThat(after.actionRequired()).isFalse();
        ArgumentCaptor<Dac7Collection> evaluated = ArgumentCaptor.forClass(Dac7Collection.class);
        verify(diligence).recordVerifiedSource(eq(user.getId()), evaluated.capture());
        assertThat(evaluated.getValue().verificationRef()).isEqualTo("session-42");
    }

    @Test
    void test5_residentSansNif_infoRequiredNonBloquant() {
        user.setCountry("ES");
        TaxInfoResponse r = service.get(UID);
        assertThat(r.collectionStatus()).isEqualTo(Dac7CollectionStatus.INFO_REQUIRED);
        assertThat(r.missingFields()).containsExactly(Dac7CollectionField.NAME, Dac7CollectionField.BIRTH_DATE,
                Dac7CollectionField.ADDRESS, Dac7CollectionField.TAX_IDENTIFICATION_NUMBER);
        // Lecture seule : aucune écriture, aucun audit, aucune ligne de registre.
        verify(users, never()).save(any());
        verify(audit, never()).log(anyString(), any(), anyString(), any(), anyMap());
        verify(diligence, never()).recordProvidedByUser(any(), any());
    }

    @Test
    void cenEstPlusMonAdresse_remplaceLAdresseDidit() {
        didit(new Dac7Address("1 rue de la République", null, "69001", "Lyon", "FR", null));

        TaxInfoResponse r = service.set(UID, identity(null, null, null, null,
                new TaxInfoRequest.AddressInput(" 5 avenue Foch ", " ", "75116", "Paris")));

        assertThat(user.getResidenceStreet()).isEqualTo("5 avenue Foch");
        assertThat(user.getResidenceLine2()).isNull();
        assertThat(user.getResidencePostalCode()).isEqualTo("75116");
        assertThat(user.getCity()).isEqualTo("Paris");
        assertThat(r.identity().addressSource()).isEqualTo(Dac7FieldSource.PROFILE);
        assertThat(r.identity().addressReplaceable()).isFalse();
        assertThat(r.readOnlyFields()).containsExactly(Dac7CollectionField.NAME, Dac7CollectionField.BIRTH_DATE);
        verify(diligence).recordProvidedByUser(user.getId(), "ADDRESS");
        verify(audit).log(eq("USER"), eq(user.getId()), eq("TRAVELER_TAX_INFO_SET"), eq(user.getId()), anyMap());
    }

    @Test
    void valeursDidit_enLectureSeule() {
        didit(null);
        assert422(() -> service.set(UID, identity(null, null, "1991-01-01", null, null)), "tax-info-read-only",
                "birthDate");
        assert422(() -> service.set(UID, identity("Aïcha", "Diallo", null, null, null)), "tax-info-read-only",
                "firstName");
        // Mêmes valeurs que la pièce : acceptées, rien à écrire.
        TaxInfoResponse r = service.set(UID, identity("awa", "DIALLO", "1990-04-12", null, null));
        assertThat(r.identity().birthDateSource()).isEqualTo(Dac7FieldSource.DIDIT);
        assertThat(user.getBirthDate()).isNull();
        verify(users, never()).save(any());
    }

    @Test
    void sansPiece_nomEtNaissanceSaisisDansLeProfil() {
        TaxInfoResponse r = service.set(UID, identity(" Jean ", "Dupont", "1985-02-28", null,
                new TaxInfoRequest.AddressInput("2 rue Neuve", "Bât. B", "75002", "Paris")));

        assertThat(user.getFirstName()).isEqualTo("Jean");
        assertThat(user.getLastName()).isEqualTo("Dupont");
        assertThat(user.getBirthDate()).isEqualTo(java.time.LocalDate.of(1985, 2, 28));
        assertThat(user.getResidenceLine2()).isEqualTo("Bât. B");
        assertThat(r.missingFields()).containsExactly(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER);
        assertThat(r.identity().nameSource()).isEqualTo(Dac7FieldSource.PROFILE);
        verify(diligence).recordProvidedByUser(user.getId(), "NAME,BIRTH_DATE,ADDRESS");
        verify(diligence, never()).recordVerifiedSourceOnce(any(), any(), any());

        // Deuxième envoi identique : rien.
        service.set(UID, identity("Jean", "Dupont", "1985-02-28", null,
                new TaxInfoRequest.AddressInput("2 rue Neuve", "Bât. B", "75002", "Paris")));
        verify(users, times(1)).save(any());
    }

    @Test
    void saisiesInvalides_422SansRecopierLaValeur() {
        assertThatThrownBy(() -> service.set(UID, identity(null, null, "12/04/1990", null, null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getMessage()).doesNotContain("12/04/1990"));
        assert422(() -> service.set(UID, identity(null, null, "2030-01-01", null, null)), "tax-info-invalid",
                "birthDate");
        assert422(() -> service.set(UID, identity(null, null, "1899-12-31", null, null)), "tax-info-invalid",
                "birthDate");
        assert422(() -> service.set(UID, identity("Jean", " ", null, null, null)), "tax-info-invalid", "lastName");
        assert422(() -> service.set(UID, identity("J".repeat(101), "Dupont", null, null, null)), "tax-info-invalid",
                "firstName");
        assert422(() -> service.set(UID, identity(null, null, null, null,
                new TaxInfoRequest.AddressInput("2 rue Neuve", null, "75002", " "))), "tax-info-invalid",
                "address.city");
        assert422(() -> service.set(UID, identity(null, null, null, null,
                new TaxInfoRequest.AddressInput("2 rue\nNeuve", null, "75002", "Paris"))), "tax-info-invalid",
                "address.street");
        assert422(() -> service.set(UID, identity(null, null, null, null,
                new TaxInfoRequest.AddressInput("2 rue Neuve", null, null, "Paris"))), "tax-info-invalid",
                "address.postalCode");
        // Pays d'émission seul, sans NIF : refusé comme dans l'ancien contrat.
        assert422(() -> service.set(UID, new TaxInfoRequest(" ", "FR", null, "Jean", "Dupont", null, null, null)),
                "tax-info-invalid", "taxIdentificationNumber");
        verify(users, never()).save(any());
    }

    @Test
    void lieuDeNaissance_seulementSansNif_effaceDesQuUnNifArrive() {
        service.set(UID, identity(null, null, null, " Dakar, Sénégal ", null));
        assertThat(user.getTaxBirthPlace()).isEqualTo("Dakar, Sénégal");
        assertThat(service.get(UID).identity().birthPlaceProvided()).isTrue();
        // Sans NIF : toujours INFO_REQUIRED (pas de NOTIN, pas de complétude par le seul lieu de naissance).
        assertThat(service.get(UID).missingFields()).contains(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER);

        assert422(() -> service.set(UID, new TaxInfoRequest("1234567890123", "FR", null, null, null, null, "Dakar",
                null)), "tax-info-invalid", "birthPlace");

        service.set(UID, new TaxInfoRequest("1234567890123", "FR", null));
        assertThat(user.getTaxBirthPlace()).isNull();
        verify(diligence).recordProvidedByUser(user.getId(), "TAX_IDENTIFICATION_NUMBER,BIRTH_PLACE");
    }

    @Test
    void lieuDeNaissance_videLEfface() {
        user.setTaxBirthPlace("Dakar");
        service.set(UID, identity(null, null, null, "", null));
        assertThat(user.getTaxBirthPlace()).isNull();
        assert422(() -> service.set(UID, identity(null, null, null, "X".repeat(121), null)), "tax-info-invalid",
                "birthPlace");
    }

    @Test
    void tvaSeule_ajouteeOuEffacee() {
        user.setTaxIdentificationNumber("1234567890123");
        user.setTaxCountry("FR");
        service.set(UID, new TaxInfoRequest(null, null, "FR12345678901"));
        assertThat(user.getVatNumber()).isEqualTo("FR12345678901");
        service.set(UID, new TaxInfoRequest(null, null, ""));
        assertThat(user.getVatNumber()).isNull();
        assertThat(user.getTaxIdentificationNumber()).isEqualTo("1234567890123");
    }

    @Test
    void diditInjoignable_signaleSansBloquer() {
        when(port.verifiedIdentity(user.getId())).thenReturn(Dac7VerifiedIdentityPort.Lookup.unavailable());
        TaxInfoResponse r = service.get(UID);
        assertThat(r.identity().identityCheckUnavailable()).isTrue();
        assertThat(r.collectionStatus()).isEqualTo(Dac7CollectionStatus.INFO_REQUIRED);
    }

    @Test
    void professionnel_identiteNonCollecteeIci() {
        user.setTravelerStatus(com.yadony.api.travelerstatus.TravelerStatus.PROFESSIONAL);
        user.setBusinessCountry("FR");
        TaxInfoResponse r = service.get(UID);
        assertThat(r.missingFields()).containsExactly(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER,
                Dac7CollectionField.BUSINESS_IDENTITY);
        assertThat(r.actionRequired()).isTrue();
        assertThat(r.identity()).isNull();

        TaxInfoResponse withTin = service.set(UID, new TaxInfoRequest("1234567890123", "FR", null));
        assertThat(withTin.collectionStatus()).isEqualTo(Dac7CollectionStatus.INFO_REQUIRED);
        assertThat(withTin.missingFields()).containsExactly(Dac7CollectionField.BUSINESS_IDENTITY);
        assertThat(withTin.actionRequired()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BG", "CZ", "DK", "HU", "PL", "RO", "SE"})
    void paysDeResidence_etatsHorsCatalogueDePaiement(String country) {
        user.setCountry(null);
        TaxInfoResponse unknown = service.get(UID);
        assertThat(unknown.collectionStatus()).isEqualTo(Dac7CollectionStatus.RESIDENCE_INCONNUE);
        assertThat(unknown.residenceCountryChoices()).contains(country, "FR", "CI");

        TaxInfoResponse r = service.set(UID, new TaxInfoRequest(null, null, null, null, null, null, null, null,
                country.toLowerCase(java.util.Locale.ROOT)));

        assertThat(r.collectionStatus()).isEqualTo(Dac7CollectionStatus.INFO_REQUIRED);
        assertThat(r.residenceCountry()).isEqualTo(country);
        assertThat(r.residenceCountryChoices()).isNull();
        // Pays de DAC7 seulement : le pays du profil (paiements, devises) n'est pas touché.
        assertThat(user.getCountry()).isNull();
        assertThat(user.getTaxDeclaredResidenceCountry()).isEqualTo(country);
        verify(diligence).recordProvidedByUser(user.getId(), "RESIDENCE_COUNTRY");
    }

    @Test
    void paysDeResidence_refusSiProfilRenseigneOuPaysHorsListe() {
        user.setCountry(null);
        assert422(() -> service.set(UID, new TaxInfoRequest(null, null, null, null, null, null, null, null, "ZZ")),
                "tax-info-invalid", "residenceCountry");
        assert422(() -> service.set(UID, new TaxInfoRequest(null, null, null, null, null, null, null, null, "US2")),
                "tax-info-invalid", "residenceCountry");
        user.setCountry("FR");
        assert422(() -> service.set(UID, new TaxInfoRequest(null, null, null, null, null, null, null, null, "SE")),
                "tax-info-invalid", "residenceCountry");
        verify(users, never()).save(any());
    }

    @Test
    void paysDeResidence_etIdentiteDansLaMemeRequete() {
        user.setCountry(null);
        TaxInfoResponse r = service.set(UID, new TaxInfoRequest(null, null, null, null, null, "1990-04-12", null,
                null, "PL"));
        assertThat(r.identity().birthDate()).isEqualTo(BIRTH);
        assertThat(r.missingFields()).doesNotContain(Dac7CollectionField.BIRTH_DATE);
        // Même valeur une deuxième fois : rien.
        service.set(UID, new TaxInfoRequest(null, null, null, null, null, null, null, null, "PL"));
        verify(users, times(1)).save(any());
    }

    @Test
    void dto_jamaisDeValeurDansToString() {
        TaxInfoRequest.AddressInput address = new TaxInfoRequest.AddressInput("5 avenue Foch", null, "75116", "Paris");
        assertThat(address.toString()).doesNotContain("Foch");
        assertThat(new TaxInfoRequest("1234567890123", "FR", null, "Awa", "Diallo", "1990-04-12", "Dakar", address)
                .toString()).doesNotContain("1234567890123").doesNotContain("Dakar").doesNotContain("Awa");
        assertThat(new com.yadony.api.dac7.dto.TaxInfoResponse.TaxAddress("5 avenue Foch", null, "75116", "Paris",
                "FR").toString()).doesNotContain("Foch");
        assertThat(new Dac7VerifiedIdentity("Awa", "Diallo", BIRTH, null).verificationRef()).isNull();
    }

    @Test
    void pieceVerifieeRelueHorsTransaction() {
        java.util.concurrent.atomic.AtomicBoolean inTransaction = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.List<Boolean> lookupsInTransaction = new java.util.ArrayList<>();
        when(port.verifiedIdentity(user.getId())).thenAnswer(i -> {
            lookupsInTransaction.add(inTransaction.get());
            return Dac7VerifiedIdentityPort.Lookup.none();
        });
        org.springframework.transaction.support.TransactionOperations tracking =
                new org.springframework.transaction.support.TransactionOperations() {
                    @Override
                    public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                        inTransaction.set(true);
                        try {
                            return action.doInTransaction(null);
                        } finally {
                            inTransaction.set(false);
                        }
                    }
                };
        TravelerTaxInfoService tracked = new TravelerTaxInfoService(users, audit, diligence, policy,
                new Dac7SolicitationPolicy(policy, readModel), new Dac7CollectionPolicy(policy, new Dac7IdentityResolver(port), clock), clock,
                tracking);

        tracked.set(UID, new TaxInfoRequest("1234567890123", "FR", null));
        tracked.get(UID);
        tracked.set(UID, new TaxInfoRequest("1234567890123", "FR", "FR12345678901"));
        tracked.get(UID);

        // Une seule lecture (cache court par voyageur), jamais dans la transaction d'écriture.
        assertThat(lookupsInTransaction).containsExactly(false);
    }
}
