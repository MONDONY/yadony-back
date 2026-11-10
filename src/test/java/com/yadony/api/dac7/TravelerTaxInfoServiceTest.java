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
    private final TravelerTaxInfoService service = new TravelerTaxInfoService(users, audit, diligence, policy,
            new Dac7SolicitationPolicy(policy, readModel), Clock.fixed(NOW, ZoneOffset.UTC));
    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setFirebaseUid(UID);
        user.setCountry("FR");
        when(users.findByFirebaseUid(UID)).thenReturn(Optional.of(user));
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
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
        TaxInfoResponse r = service.set(UID, new TaxInfoRequest("  12 345.678-90/1 ", " fr ", " fr123 "));

        assertThat(user.getTaxIdentificationNumber()).isEqualTo("12 345.678-90/1");
        assertThat(user.getTaxCountry()).isEqualTo("FR");
        assertThat(user.getVatNumber()).isEqualTo("FR123");
        assertThat(user.getTaxInfoProvidedAt()).isEqualTo(NOW);
        assertThat(r.provided()).isTrue();
        assertThat(r.maskedTaxIdentificationNumber()).isEqualTo("****90/1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("USER"), eq(user.getId()), eq("TRAVELER_TAX_INFO_SET"), eq(user.getId()),
                payload.capture());
        assertThat(payload.getValue().toString()).doesNotContain("345").doesNotContain("FR123")
                .doesNotContain("FR");
        verify(diligence).record(user.getId(), Dac7DueDiligenceAction.PROVIDED, Dac7DueDiligenceChannel.IN_APP,
                null, null);
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
        verify(diligence, times(1)).record(any(), any(), any(), any(), any());
    }

    @Test
    void put_numeroDeTvaAjoute_nouvelleEcriture() {
        service.set(UID, new TaxInfoRequest(NIF, "FR", null));
        service.set(UID, new TaxInfoRequest(NIF, "FR", "FR12345678901"));

        assertThat(user.getVatNumber()).isEqualTo("FR12345678901");
        verify(audit, times(2)).log(anyString(), any(), eq("TRAVELER_TAX_INFO_SET"), any(), anyMap());
        verify(diligence, times(2)).record(any(), eq(Dac7DueDiligenceAction.PROVIDED), any(), any(), any());
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
}
