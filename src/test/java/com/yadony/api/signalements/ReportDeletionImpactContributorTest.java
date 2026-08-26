package com.yadony.api.signalements;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportDeletionImpactContributorTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();

    @Mock ReportRepository reportRepository;

    private ReportEntity report(UUID reporterId, ReportTargetType targetType, UUID targetId) {
        ReportEntity r = new ReportEntity();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setReporterId(reporterId);
        r.setTargetType(targetType);
        r.setTargetId(targetId);
        r.setStatus(ReportStatus.OPEN);
        return r;
    }

    private ReportDeletionImpactContributor contributor() {
        return new ReportDeletionImpactContributor(reportRepository);
    }

    @Test
    @DisplayName("un signalement visant le compte nomme son auteur")
    void reportTargeting_namesAuthor() {
        when(reportRepository.findByStatusAndTargetTypeAndTargetId(
                ReportStatus.OPEN, ReportTargetType.USER, USER_ID))
                .thenReturn(List.of(report(OTHER_ID, ReportTargetType.USER, USER_ID)));
        when(reportRepository.findByStatusAndReporterId(ReportStatus.OPEN, USER_ID))
                .thenReturn(List.of());

        ImpactFinding finding = contributor().contribute(USER_ID).getFirst();

        assertThat(finding.code()).isEqualTo("REPORT_TARGETING");
        assertThat(finding.severity()).isEqualTo(ImpactSeverity.WARNING);
        assertThat(finding.affectedParties())
                .extracting(ImpactFinding.AffectedParty::userId).containsExactly(OTHER_ID);
    }

    // Un signalement peut viser une annonce ou un message : la cible n'est alors pas un compte
    // et il n'y a personne à nommer, mais le signalement doit rester compté.
    @Test
    @DisplayName("un signalement écrit par le compte ne nomme une cible que si c'est un compte")
    void reportAuthored_namesTargetOnlyWhenUser() {
        when(reportRepository.findByStatusAndTargetTypeAndTargetId(
                ReportStatus.OPEN, ReportTargetType.USER, USER_ID)).thenReturn(List.of());
        when(reportRepository.findByStatusAndReporterId(ReportStatus.OPEN, USER_ID))
                .thenReturn(List.of(
                        report(USER_ID, ReportTargetType.USER, OTHER_ID),
                        report(USER_ID, ReportTargetType.ANNOUNCEMENT, UUID.randomUUID())));

        ImpactFinding finding = contributor().contribute(USER_ID).getFirst();

        assertThat(finding.code()).isEqualTo("REPORT_AUTHORED");
        assertThat(finding.count()).isEqualTo(2);
        assertThat(finding.affectedParties())
                .extracting(ImpactFinding.AffectedParty::userId).containsExactly(OTHER_ID);
    }

    // Le décompte et la liste de contreparties peuvent diverger : un signalement anonyme reste
    // dans le count mais n'a personne à nommer. Ce test garantit que retirer le filtre != null
    // ferait échouer la suite — un count=2 avec une seule contrepartie est le résultat attendu.
    @Test
    @DisplayName("un signalement anonyme reste compté mais ne nomme personne")
    void reportTargeting_anonymousReporterCountedButNotNamed() {
        when(reportRepository.findByStatusAndTargetTypeAndTargetId(
                ReportStatus.OPEN, ReportTargetType.USER, USER_ID))
                .thenReturn(List.of(
                        report(OTHER_ID, ReportTargetType.USER, USER_ID),   // auteur connu
                        report(null,     ReportTargetType.USER, USER_ID))); // signalement anonyme
        when(reportRepository.findByStatusAndReporterId(ReportStatus.OPEN, USER_ID))
                .thenReturn(List.of());

        ImpactFinding finding = contributor().contribute(USER_ID).getFirst();

        assertThat(finding.code()).isEqualTo("REPORT_TARGETING");
        // Deux signalements dans le décompte, mais un seul auteur identifiable.
        assertThat(finding.count()).isEqualTo(2);
        assertThat(finding.affectedParties())
                .extracting(ImpactFinding.AffectedParty::userId)
                .containsExactly(OTHER_ID);
    }

    // Un compte peut théoriquement se signaler lui-même. Dans ce cas il n'est pas une contrepartie
    // — le rapport ne doit pas l'afficher comme un tiers affecté par sa propre suppression.
    // Le signalement reste dans le décompte : le supprimer du count masquerait l'information.
    @Test
    @DisplayName("un auto-signalement ne produit aucune contrepartie, mais reste compté")
    void reportTargeting_selfReportNoCounterparty() {
        when(reportRepository.findByStatusAndTargetTypeAndTargetId(
                ReportStatus.OPEN, ReportTargetType.USER, USER_ID))
                .thenReturn(List.of(report(USER_ID, ReportTargetType.USER, USER_ID)));
        when(reportRepository.findByStatusAndReporterId(ReportStatus.OPEN, USER_ID))
                .thenReturn(List.of());

        ImpactFinding finding = contributor().contribute(USER_ID).getFirst();

        assertThat(finding.code()).isEqualTo("REPORT_TARGETING");
        assertThat(finding.count()).isEqualTo(1);
        assertThat(finding.affectedParties()).isEmpty();
    }

    @Test
    @DisplayName("aucune modération en cours, rien à rapporter")
    void noReport_reportsNothing() {
        when(reportRepository.findByStatusAndTargetTypeAndTargetId(
                ReportStatus.OPEN, ReportTargetType.USER, USER_ID)).thenReturn(List.of());
        when(reportRepository.findByStatusAndReporterId(ReportStatus.OPEN, USER_ID))
                .thenReturn(List.of());

        assertThat(contributor().contribute(USER_ID)).isEmpty();
    }
}
