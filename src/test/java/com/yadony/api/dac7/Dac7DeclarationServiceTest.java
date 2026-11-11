package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.Dac7DeclarationService.Declaration;
import com.yadony.api.dac7.Dac7Report.QuarterReport;
import com.yadony.api.dac7.Dac7Report.TravelerReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Déclaration DAC7 : identité, exclusions, partenaires non transmis, blocages")
class Dac7DeclarationServiceTest {

    private final Dac7ReportService reports = mock(Dac7ReportService.class);
    private final EcbExchangeRateProvider rates = mock(EcbExchangeRateProvider.class);
    private final UserRepository users = mock(UserRepository.class);
    private final Dac7AdminReadModel reads = mock(Dac7AdminReadModel.class);
    private final Dac7VerifiedIdentityPort port = mock(Dac7VerifiedIdentityPort.class);
    private final Clock clock = Clock.fixed(Instant.parse("2027-01-15T09:00:00Z"), ZoneOffset.UTC);

    private Dac7DeclarationService service(Dac7Properties properties, Dac7ExportProperties export) {
        return new Dac7DeclarationService(reports, rates, new Dac7IdentityResolver(port), users, reads,
                new Dac7ResidencyPolicy(properties), properties, export, clock);
    }

    private static Dac7ExportProperties configured() {
        return new Dac7ExportProperties(null, "123456789", "Yadony SAS", null, null, null, null, "Paris");
    }

    private static List<QuarterReport> quarters(String q1Net) {
        QuarterReport empty = new QuarterReport(2, 0, List.of(), new BigDecimal("0.00"), new BigDecimal("0.00"), true,
                true);
        return List.of(new QuarterReport(1, 1, List.of(), new BigDecimal(q1Net), new BigDecimal("0.20"), true, true),
                empty, new QuarterReport(3, 0, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, true),
                new QuarterReport(4, 0, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, true));
    }

    private static UserEntity user(UUID id, boolean complete) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirstName("Awa");
        u.setLastName("Diallo");
        u.setCountry("FR");
        if (complete) {
            u.setBirthDate(LocalDate.of(1990, 1, 2));
            u.setResidenceStreet("1 rue de Paris");
            u.setCity("Lyon");
        }
        return u;
    }

    @Test
    @DisplayName("taux BCE assurés avant le calcul ; contrepartie arrondie nulle exclue ; partenaire non transmis bloque")
    void exclusionsEtBlocages() {
        UUID ok = UUID.randomUUID();
        UUID tiny = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID ivorian = UUID.randomUUID();
        when(reports.annualReport(2026)).thenReturn(new Dac7Report(2026,
                List.of(new TravelerReport(ok, "FR", true, Dac7IdentityStatus.COMPLETE, quarters("95.00")),
                        new TravelerReport(tiny, "FR", true, Dac7IdentityStatus.COMPLETE, quarters("0.40"))),
                List.of(new TravelerReport(deleted, "FR", true, Dac7IdentityStatus.INCOMPLETE, quarters("10.00"))),
                List.of(), List.of(), List.of(), List.of(ivorian), List.of(), List.of()));
        when(users.findAllById(any())).thenReturn(List.of(user(ok, true), user(tiny, true), user(ivorian, false)));
        when(reads.lastDiligence(any())).thenReturn(Map.of(ok, Instant.parse("2026-12-01T00:00:00Z")));
        when(port.verifiedIdentity(any())).thenReturn(Optional.empty());

        Declaration d = service(new Dac7Properties(false, List.of("SN", "zz")), configured()).declaration(2026, true);

        InOrder order = inOrder(rates, reports);
        order.verify(rates).ensureCoverage(2026);
        order.verify(reports).annualReport(2026);
        assertThat(d.sellers()).hasSize(3);
        var byId = new java.util.HashMap<UUID, Dac7DeclarationService.Seller>();
        d.sellers().forEach(s -> byId.put(s.userId(), s));
        assertThat(byId.get(ok).exclusions()).isEmpty();
        assertThat(byId.get(ok).lastDiligenceAt()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(byId.get(tiny).exclusions()).containsExactly(Dac7DeclarationService.ZERO_CONSIDERATION);
        assertThat(byId.get(deleted).displayName()).isEqualTo("Compte supprimé");
        assertThat(byId.get(deleted).identity().missing()).containsExactly("ACCOUNT_DELETED");
        assertThat(d.others()).singleElement().satisfies(o -> {
            assertThat(o.residency()).isEqualTo(Dac7ResidencyStatus.NON_DECLARABLE);
            assertThat(o.identityStatus()).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
        });
        // SN configuré mais non transmis par la DGFiP en 2026 (annexe 6.1).
        // « zz » n'est pas un code ISO : ignoré par la politique éteinte et listé invalide par le calcul (mock ici).
        assertThat(d.invalidPartnerJurisdictions()).containsExactly("SN");
        assertThat(d.blockers()).containsExactly("IDENTITY_INCOMPLETE", "INVALID_PARTNER_JURISDICTION");
    }

    @Test
    @DisplayName("aucun vendeur et opérateur non configuré : NO_SELLERS et OPERATOR_CONFIG_MISSING")
    void aucunVendeur() {
        when(reports.annualReport(2026)).thenReturn(new Dac7Report(2026, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of()));
        Declaration d = service(new Dac7Properties(false, null),
                new Dac7ExportProperties(null, null, null, null, null, null, null, null)).declaration(2026, false);
        assertThat(d.blockers()).containsExactly("NO_SELLERS", "OPERATOR_CONFIG_MISSING");
    }

    @Test
    @DisplayName("année hors 2023 à l'année en cours : 422 dac7-year-invalid ; arrondi à l'euro HALF_UP")
    void anneeEtArrondi() {
        Dac7DeclarationService s = service(new Dac7Properties(false, null), configured());
        assertThat(s.requireYear(2026)).isEqualTo(2026);
        for (Integer year : new Integer[]{null, 2022, 2028}) {
            assertThatThrownBy(() -> s.requireYear(year)).isInstanceOfSatisfying(YadonyBusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo("dac7-year-invalid"));
        }
        assertThat(Dac7DeclarationService.roundEuro(new BigDecimal("0.49"))).isZero();
        assertThat(Dac7DeclarationService.roundEuro(new BigDecimal("0.50"))).isEqualTo(1);
        assertThat(Dac7DeclarationService.roundEuro(new BigDecimal("1250.50"))).isEqualTo(1251);
        assertThat(Dac7DeclarationService.roundEuro(null)).isZero();
    }
}
