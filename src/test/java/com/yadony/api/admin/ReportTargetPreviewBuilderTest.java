package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminReportContextResponse.Fact;
import com.yadony.api.admin.dto.AdminReportContextResponse.TargetPreview;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportTargetPreviewBuilderTest {

    @Mock AnnouncementRepository announcementRepo;
    @Mock PackageRequestRepository packageRequestRepo;
    @Mock BidRepository bidRepo;
    @InjectMocks ReportTargetPreviewBuilder builder;

    private final UUID targetId = UUID.randomUUID();

    private ReportEntity report(ReportTargetType type, UUID id) {
        ReportEntity r = new ReportEntity();
        r.setTargetType(type);
        r.setTargetId(id);
        return r;
    }

    private static AnnouncementEntity announcement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.of(2026, 11, 12));
        a.setTransportMode(TransportMode.PLANE);
        a.setPricePerKg(new BigDecimal("8.50"));
        a.setCurrency("EUR");
        a.setAvailableKg(new BigDecimal("12.0"));
        a.setTotalKg(new BigDecimal("20"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setDescription("  Valise cabine, pas de liquides.  ");
        return a;
    }

    private static Fact fact(TargetPreview p, String label) {
        return p.facts().stream().filter(f -> f.label().equals(label)).findFirst().orElse(null);
    }

    // ---- annonce ----

    @Test
    void annonce_trajetDepartTransportPrixPoidsEtTexte() {
        when(announcementRepo.findById(targetId)).thenReturn(Optional.of(announcement()));

        TargetPreview p = builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId));

        assertThat(p.kind()).isEqualTo("ANNOUNCEMENT");
        assertThat(p.status()).isEqualTo("ACTIVE");
        assertThat(fact(p, "Trajet").value()).isEqualTo("Paris → Dakar");
        assertThat(fact(p, "Départ").value()).isEqualTo("12/11/2026");
        assertThat(fact(p, "Transport").value()).isEqualTo("Avion");
        assertThat(fact(p, "Prix").value()).isEqualTo("8,5 EUR / kg");
        assertThat(fact(p, "Poids").value()).isEqualTo("12 kg disponibles sur 20 kg");
        assertThat(p.description()).isEqualTo("Valise cabine, pas de liquides.");
    }

    @Test
    void annonce_champsAbsents_neSontPasAffiches() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureCity("Lyon");
        a.setArrivalCity("Abidjan");
        when(announcementRepo.findById(targetId)).thenReturn(Optional.of(a));

        TargetPreview p = builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId));

        assertThat(p.facts()).extracting(Fact::label).containsExactly("Trajet");
        assertThat(p.description()).isNull();
    }

    @Test
    void annonce_tousLesModesDeTransportEnFrancais() {
        // Voiture et bus forment un seul groupe « Route », comme dans la recherche et les filtres admin.
        String[][] expected = {{"PLANE", "Avion"}, {"CAR", "Route"}, {"TRAIN", "Train"}, {"BUS", "Route"},
                {"BOAT", "Bateau"}, {"OTHER", "Autre"}};
        for (String[] e : expected) {
            AnnouncementEntity a = announcement();
            a.setTransportMode(TransportMode.valueOf(e[0]));
            when(announcementRepo.findById(targetId)).thenReturn(Optional.of(a));
            assertThat(fact(builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId)), "Transport").value())
                    .isEqualTo(e[1]);
        }
    }

    @Test
    void transport_carAndBusReadRoute() {
        for (TransportMode mode : new TransportMode[] {TransportMode.CAR, TransportMode.BUS}) {
            AnnouncementEntity a = announcement();
            a.setTransportMode(mode);
            when(announcementRepo.findById(targetId)).thenReturn(Optional.of(a));
            assertThat(fact(builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId)), "Transport").value())
                    .isEqualTo("Route");
        }
    }

    @Test
    void annonce_bateauAuM3_prixEtVolumeEnM3_jamaisDeKilosVides() {
        AnnouncementEntity a = announcement();
        a.setTransportMode(TransportMode.BOAT);
        a.setPricePerKg(null);
        a.setAvailableKg(null);
        a.setTotalKg(null);
        a.setPricePerM3(new BigDecimal("150.00"));
        a.setVolumeCapacityM3(new BigDecimal("10.000"));
        a.setAvailableVolumeM3(new BigDecimal("2.500"));
        when(announcementRepo.findById(targetId)).thenReturn(Optional.of(a));

        TargetPreview p = builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId));

        assertThat(fact(p, "Transport").value()).isEqualTo("Bateau");
        assertThat(fact(p, "Prix").value()).isEqualTo("150 EUR / m³");
        assertThat(fact(p, "Volume").value()).isEqualTo("2,5 m³ disponibles sur 10 m³");
        assertThat(fact(p, "Poids")).isNull();
        assertThat(p.facts()).extracting(Fact::value).noneMatch(v -> v.contains("null"));
    }

    @Test
    void annonce_bateauAuM3_volumeSeulementDisponibleOuSeulementTotal() {
        AnnouncementEntity onlyAvailable = announcement();
        onlyAvailable.setTransportMode(TransportMode.BOAT);
        onlyAvailable.setPricePerM3(new BigDecimal("150"));
        onlyAvailable.setAvailableVolumeM3(new BigDecimal("3"));
        when(announcementRepo.findById(targetId)).thenReturn(Optional.of(onlyAvailable));
        assertThat(fact(builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId)), "Volume").value())
                .isEqualTo("3 m³ disponibles");

        AnnouncementEntity onlyTotal = announcement();
        onlyTotal.setTransportMode(TransportMode.BOAT);
        onlyTotal.setPricePerM3(new BigDecimal("150"));
        onlyTotal.setVolumeCapacityM3(new BigDecimal("8"));
        when(announcementRepo.findById(targetId)).thenReturn(Optional.of(onlyTotal));
        assertThat(fact(builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId)), "Volume").value())
                .isEqualTo("8 m³");

        AnnouncementEntity none = announcement();
        none.setTransportMode(TransportMode.BOAT);
        none.setPricePerM3(new BigDecimal("150"));
        when(announcementRepo.findById(targetId)).thenReturn(Optional.of(none));
        assertThat(fact(builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId)), "Volume")).isNull();
    }

    @Test
    void demandeDEnvoi_bateau_modeVolumeEtDimensions() {
        PackageRequestEntity p = new PackageRequestEntity();
        p.setDepartureCity("Marseille");
        p.setArrivalCity("Dakar");
        p.setTransportMode(TransportMode.BOAT);
        p.setVolumeM3(new BigDecimal("0.096"));
        p.setLengthCm(60);
        p.setWidthCm(40);
        p.setHeightCm(40);
        when(packageRequestRepo.findById(targetId)).thenReturn(Optional.of(p));

        TargetPreview preview = builder.build(report(ReportTargetType.PACKAGE_REQUEST, targetId));

        assertThat(fact(preview, "Transport").value()).isEqualTo("Bateau");
        assertThat(fact(preview, "Volume").value()).isEqualTo("0,096 m³");
        assertThat(fact(preview, "Dimensions").value()).isEqualTo("60 × 40 × 40 cm");
    }

    @Test
    void demandeDEnvoi_peuImporteEtDimensionsIncompletes_sansLigne() {
        PackageRequestEntity p = new PackageRequestEntity();
        p.setDepartureCity("Paris");
        p.setArrivalCity("Dakar");
        p.setTransportMode(null);
        p.setLengthCm(60);
        p.setWidthCm(40);
        when(packageRequestRepo.findById(targetId)).thenReturn(Optional.of(p));

        TargetPreview preview = builder.build(report(ReportTargetType.PACKAGE_REQUEST, targetId));

        assertThat(fact(preview, "Transport")).isNull();
        assertThat(fact(preview, "Dimensions")).isNull();
        assertThat(fact(preview, "Volume")).isNull();
    }

    @Test
    void annonce_poidsSeulementDisponibleOuSeulementTotal() {
        AnnouncementEntity onlyAvailable = announcement();
        onlyAvailable.setTotalKg(null);
        when(announcementRepo.findById(targetId)).thenReturn(Optional.of(onlyAvailable));
        assertThat(fact(builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId)), "Poids").value())
                .isEqualTo("12 kg disponibles");

        AnnouncementEntity onlyTotal = announcement();
        onlyTotal.setAvailableKg(null);
        when(announcementRepo.findById(targetId)).thenReturn(Optional.of(onlyTotal));
        assertThat(fact(builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId)), "Poids").value())
                .isEqualTo("20 kg");
    }

    @Test
    void annonce_texteLongTronqueAvecPointsDeSuspension() {
        AnnouncementEntity a = announcement();
        a.setDescription("x".repeat(900));
        when(announcementRepo.findById(targetId)).thenReturn(Optional.of(a));

        String d = builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId)).description();

        assertThat(d).hasSize(ReportTargetPreviewBuilder.DESCRIPTION_MAX).endsWith("…");
    }

    @Test
    void annonce_devisePropreAAnnonce() {
        AnnouncementEntity a = announcement();
        a.setCurrency("XOF");
        a.setPricePerKg(new BigDecimal("5000"));
        when(announcementRepo.findById(targetId)).thenReturn(Optional.of(a));

        assertThat(fact(builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId)), "Prix").value())
                .isEqualTo("5000 XOF / kg");
    }

    // ---- demande d'envoi ----

    @Test
    void demandeDEnvoi_trajetDateAvecToleranceBudgetNegociable() {
        PackageRequestEntity p = new PackageRequestEntity();
        p.setDepartureCity("Paris");
        p.setArrivalCity("Bamako");
        p.setDesiredDate(LocalDate.of(2026, 12, 1));
        p.setDateToleranceDays((short) 3);
        p.setWeightKg(new BigDecimal("4.5"));
        p.setContentCategory("CLOTHES");
        p.setTargetPriceEur(new BigDecimal("40"));
        p.setNegotiable(true);
        p.setStatus(PackageRequestStatus.OPEN);
        p.setDescription("Vêtements d'enfants");
        when(packageRequestRepo.findById(targetId)).thenReturn(Optional.of(p));

        TargetPreview preview = builder.build(report(ReportTargetType.PACKAGE_REQUEST, targetId));

        assertThat(preview.kind()).isEqualTo("PACKAGE_REQUEST");
        assertThat(preview.status()).isEqualTo("OPEN");
        assertThat(fact(preview, "Date souhaitée").value()).isEqualTo("01/12/2026 (± 3 j)");
        assertThat(fact(preview, "Poids").value()).isEqualTo("4,5 kg");
        assertThat(fact(preview, "Contenu").value()).isEqualTo("CLOTHES");
        assertThat(fact(preview, "Budget").value()).isEqualTo("40 EUR (négociable)");
        assertThat(preview.description()).isEqualTo("Vêtements d'enfants");
    }

    @Test
    void demandeDEnvoi_sansToleranceNiBudget_ouBudgetFerme() {
        PackageRequestEntity p = new PackageRequestEntity();
        p.setDepartureCity("Paris");
        p.setArrivalCity("Bamako");
        p.setDesiredDate(LocalDate.of(2026, 12, 1));
        p.setDateToleranceDays((short) 0);
        p.setNegotiable(false);
        p.setTargetPriceEur(new BigDecimal("40"));
        when(packageRequestRepo.findById(targetId)).thenReturn(Optional.of(p));

        TargetPreview preview = builder.build(report(ReportTargetType.PACKAGE_REQUEST, targetId));

        assertThat(fact(preview, "Date souhaitée").value()).isEqualTo("01/12/2026");
        assertThat(fact(preview, "Budget").value()).isEqualTo("40 EUR");
        assertThat(fact(preview, "Poids")).isNull();
    }

    private String budget(String currency, String montant) {
        PackageRequestEntity p = new PackageRequestEntity();
        p.setDepartureCity("Paris");
        p.setArrivalCity("Abidjan");
        p.setCurrency(currency);
        p.setTargetPriceEur(new BigDecimal(montant));
        p.setNegotiable(false);
        when(packageRequestRepo.findById(targetId)).thenReturn(Optional.of(p));
        return fact(builder.build(report(ReportTargetType.PACKAGE_REQUEST, targetId)), "Budget").value();
    }

    @Test
    void demandeDEnvoi_budgetDansLaDeviseDeLaDemande() {
        assertThat(budget("XOF", "25000")).isEqualTo("25000 XOF");
    }

    @Test
    void demandeDEnvoi_budgetEnDollars() {
        assertThat(budget("USD", "112.19")).isEqualTo("112,19 USD");
    }

    @Test
    void demandeDEnvoi_budgetEnEuros() {
        assertThat(budget("EUR", "37.38")).isEqualTo("37,38 EUR");
    }

    @Test
    void demandeDEnvoi_deviseAbsente_replieSurEuro() {
        assertThat(budget(null, "40")).isEqualTo("40 EUR");
    }

    // ---- offre ----

    @Test
    void offre_annonceConcerneePoidsContenuEtMontant() {
        UUID announcementId = UUID.randomUUID();
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setWeightKg(new BigDecimal("3"));
        b.setContentCategory("DOCUMENTS");
        b.setNegotiatedNetEur(new BigDecimal("25.00"));
        b.setStatus(BidStatus.NEGOTIATING);
        b.setDescription("Dossier administratif");
        when(bidRepo.findById(targetId)).thenReturn(Optional.of(b));
        when(announcementRepo.findById(announcementId)).thenReturn(Optional.of(announcement()));

        TargetPreview p = builder.build(report(ReportTargetType.BID, targetId));

        assertThat(p.kind()).isEqualTo("BID");
        assertThat(p.status()).isEqualTo("NEGOTIATING");
        assertThat(fact(p, "Annonce").value()).isEqualTo("Paris → Dakar · 12/11/2026");
        assertThat(fact(p, "Poids").value()).isEqualTo("3 kg");
        assertThat(fact(p, "Contenu").value()).isEqualTo("DOCUMENTS");
        assertThat(fact(p, "Montant négocié").value()).isEqualTo("25 EUR");
        assertThat(p.description()).isEqualTo("Dossier administratif");
    }

    @Test
    void offre_annonceDisparue_resteLisibleSansLigneAnnonce() {
        UUID announcementId = UUID.randomUUID();
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setWeightKg(new BigDecimal("3"));
        when(bidRepo.findById(targetId)).thenReturn(Optional.of(b));
        when(announcementRepo.findById(announcementId)).thenReturn(Optional.empty());

        TargetPreview p = builder.build(report(ReportTargetType.BID, targetId));

        assertThat(fact(p, "Annonce")).isNull();
        assertThat(fact(p, "Poids").value()).isEqualTo("3 kg");
    }

    @Test
    void offre_sansAnnonceLiee_neCherchePasUneAnnonce() {
        BidEntity b = new BidEntity();
        b.setWeightKg(new BigDecimal("3"));
        when(bidRepo.findById(targetId)).thenReturn(Optional.of(b));

        assertThat(builder.build(report(ReportTargetType.BID, targetId)).facts()).extracting(Fact::label).containsExactly("Poids");
        verifyNoInteractions(announcementRepo);
    }

    @Test
    void offre_bateau_volumeEnM3() {
        BidEntity b = new BidEntity();
        b.setVolumeM3(new BigDecimal("1.200"));
        when(bidRepo.findById(targetId)).thenReturn(Optional.of(b));

        TargetPreview p = builder.build(report(ReportTargetType.BID, targetId));

        assertThat(fact(p, "Volume").value()).isEqualTo("1,2 m³");
        assertThat(fact(p, "Poids")).isNull();
    }

    // ---- sans aperçu ----

    @Test
    void ciblesSansApercu_null() {
        for (ReportTargetType type : new ReportTargetType[] {ReportTargetType.USER, ReportTargetType.MESSAGE,
                ReportTargetType.RATING, ReportTargetType.APP}) {
            assertThat(builder.build(report(type, targetId))).isNull();
        }
        verifyNoInteractions(announcementRepo, packageRequestRepo, bidRepo);
    }

    @Test
    void cibleDisparueOuSansIdentifiant_null() {
        when(announcementRepo.findById(targetId)).thenReturn(Optional.empty());
        when(packageRequestRepo.findById(targetId)).thenReturn(Optional.empty());
        when(bidRepo.findById(targetId)).thenReturn(Optional.empty());

        assertThat(builder.build(report(ReportTargetType.ANNOUNCEMENT, targetId))).isNull();
        assertThat(builder.build(report(ReportTargetType.PACKAGE_REQUEST, targetId))).isNull();
        assertThat(builder.build(report(ReportTargetType.BID, targetId))).isNull();
        assertThat(builder.build(report(ReportTargetType.ANNOUNCEMENT, null))).isNull();
        assertThat(builder.build(new ReportEntity())).isNull();
    }

    @Test
    void formatDesNombres_virguleFrancaiseSansZerosInutiles() {
        assertThat(ReportTargetPreviewBuilder.number(new BigDecimal("8.50"))).isEqualTo("8,5");
        assertThat(ReportTargetPreviewBuilder.number(new BigDecimal("1200.000"))).isEqualTo("1200");
        assertThat(ReportTargetPreviewBuilder.money(new BigDecimal("3"), null)).isEqualTo("3 EUR");
        assertThat(ReportTargetPreviewBuilder.shorten("   ")).isNull();
    }
}
