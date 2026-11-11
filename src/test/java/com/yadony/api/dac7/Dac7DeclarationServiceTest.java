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
        when(port.verifiedIdentity(any())).thenReturn(Dac7VerifiedIdentityPort.Lookup.none());

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
    @DisplayName("Didit en panne : IDENTITY_UNAVAILABLE (jamais INCOMPLETE) ; partage en attente ; déjà exporté ; avertissements")
    void indisponiblePartagesEtAvertissements() {
        UUID down = UUID.randomUUID();
        UUID split = UUID.randomUUID();
        when(reports.annualReport(2026)).thenReturn(new Dac7Report(2026,
                List.of(new TravelerReport(down, "FR", true, Dac7IdentityStatus.COMPLETE, quarters("95.00")),
                        new TravelerReport(split, "FR", true, Dac7IdentityStatus.COMPLETE, quarters("95.00"))),
                List.of(),
                List.of(new Dac7Report.RefundToReconcile(split, Dac7Source.PAYMENT, UUID.randomUUID(), "EUR",
                        BigDecimal.TEN, Instant.now(), 2026, Instant.now(), 2026, false, false, BigDecimal.TEN)),
                List.of(new Dac7Report.PendingSplit(split, UUID.randomUUID(), "EUR", BigDecimal.TEN, BigDecimal.ONE,
                        "CLAIMED", Instant.now())),
                List.of(), List.of(), List.of(),
                List.of(new Dac7Report.Anomaly(Dac7Report.AnomalyType.RATE_DATE_ESTIMATED, down, Dac7Source.PAYMENT,
                        UUID.randomUUID(), "USD", LocalDate.of(2026, 3, 7)))));
        // Profil sans date de naissance : seule la pièce la donnerait, et Didit ne répond pas.
        UserEntity downUser = user(down, false);
        when(users.findAllById(any())).thenReturn(List.of(downUser, user(split, true)));
        when(port.verifiedIdentity(down)).thenReturn(Dac7VerifiedIdentityPort.Lookup.unavailable());
        when(port.verifiedIdentity(split)).thenReturn(Dac7VerifiedIdentityPort.Lookup.none());
        when(reads.exportedTravelers(2026)).thenReturn(java.util.Set.of(split));

        Declaration d = service(new Dac7Properties(false, null), configured()).declaration(2026, true);

        var byId = new java.util.HashMap<UUID, Dac7DeclarationService.Seller>();
        d.sellers().forEach(s -> byId.put(s.userId(), s));
        assertThat(byId.get(down).identityStatus()).isEqualTo(Dac7IdentityStatus.UNAVAILABLE);
        assertThat(byId.get(down).exclusions()).containsExactly("IDENTITY_UNAVAILABLE");
        assertThat(byId.get(split).exclusions()).containsExactly("PENDING_SPLITS");
        assertThat(byId.get(split).alreadyExported()).isTrue();
        assertThat(d.blockers()).containsExactly("IDENTITY_UNAVAILABLE", "PENDING_SPLITS");
        // Remboursements à réconcilier et anomalies : comptés, jamais bloquants.
        assertThat(d.warnings()).isEqualTo(2);
    }

    @Test
    @DisplayName("pages et CSV (verdicts en cache) : voyageur pas encore lu = NOT_CHECKED, IDENTITY_NOT_CHECKED, jamais IDENTITY_UNAVAILABLE")
    void nonVerifie() {
        List<TravelerReport> travelers = new java.util.ArrayList<>();
        List<UserEntity> entities = new java.util.ArrayList<>();
        for (int i = 0; i < Dac7IdentityResolver.MAX_LOOKUPS_PER_REQUEST + 1; i++) {
            UUID id = new UUID(0, i + 1);
            travelers.add(new TravelerReport(id, "FR", true, Dac7IdentityStatus.COMPLETE, quarters("95.00")));
            entities.add(user(id, true));
        }
        when(reports.annualReport(2026)).thenReturn(new Dac7Report(2026, travelers, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of()));
        when(users.findAllById(any())).thenReturn(entities);
        when(port.verifiedIdentity(any())).thenReturn(Dac7VerifiedIdentityPort.Lookup.none());

        Declaration d = service(new Dac7Properties(false, null), configured())
                .declaration(2026, Dac7DeclarationService.IdentityMode.CACHED);

        Dac7DeclarationService.Seller last = d.sellers().get(d.sellers().size() - 1);
        assertThat(last.identityStatus()).isEqualTo(Dac7IdentityStatus.NOT_CHECKED);
        assertThat(last.exclusions()).containsExactly(Dac7DeclarationService.IDENTITY_NOT_CHECKED);
        assertThat(d.sellers().get(0).exclusions()).isEmpty();
        assertThat(d.blockers()).containsExactly(Dac7DeclarationService.IDENTITY_NOT_CHECKED);
    }

    @Test
    @DisplayName("année close : 2026 refusée le 20/12/2026 (422 dac7-year-not-closed), acceptée le 05/01/2027")
    void anneeClose() {
        Dac7Properties p = new Dac7Properties(false, null);
        Dac7DeclarationService december = new Dac7DeclarationService(reports, rates, new Dac7IdentityResolver(port),
                users, reads, new Dac7ResidencyPolicy(p), p, configured(),
                Clock.fixed(Instant.parse("2026-12-20T10:00:00Z"), ZoneOffset.UTC));
        assertThatThrownBy(() -> december.requireClosedYear(2026)).isInstanceOfSatisfying(
                YadonyBusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("dac7-year-not-closed"));
        Dac7DeclarationService january = new Dac7DeclarationService(reports, rates, new Dac7IdentityResolver(port),
                users, reads, new Dac7ResidencyPolicy(p), p, configured(),
                Clock.fixed(Instant.parse("2027-01-05T10:00:00Z"), ZoneOffset.UTC));
        assertThat(january.requireClosedYear(2026)).isEqualTo(2026);
        assertThatThrownBy(() -> january.requireClosedYear(2027)).isInstanceOf(YadonyBusinessException.class);
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
