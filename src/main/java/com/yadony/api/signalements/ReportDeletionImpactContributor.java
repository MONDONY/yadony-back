package com.yadony.api.signalements;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import com.yadony.api.common.deletion.UserDeletionImpactContributor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * La modération en cours autour d'un compte, dans les deux sens.
 *
 * <p>Supprimer quelqu'un pendant qu'un signalement le vise ferait perdre le motif de la décision ;
 * le supprimer alors qu'il a lui-même signalé quelqu'un laisserait la cible sans instruction.
 */
@Component
public class ReportDeletionImpactContributor implements UserDeletionImpactContributor {

    private final ReportRepository reportRepository;

    public ReportDeletionImpactContributor(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    @Override
    public List<ImpactFinding> contribute(UUID userId) {
        List<ImpactFinding> findings = new ArrayList<>();

        List<ReportEntity> targeting = reportRepository.findByStatusAndTargetTypeAndTargetId(
                ReportStatus.OPEN, ReportTargetType.USER, userId);
        if (!targeting.isEmpty()) {
            // Un signalement anonyme ou système peut avoir un reporterId nul : on ne le nomme pas.
            // Si l'auteur est le compte lui-même (auto-signalement), il n'est pas une contrepartie
            // — il reste dans le décompte mais ne doit pas apparaître comme un tiers affecté.
            List<ImpactFinding.AffectedParty> authors = targeting.stream()
                    .filter(r -> r.getReporterId() != null && !userId.equals(r.getReporterId()))
                    .map(r -> new ImpactFinding.AffectedParty(r.getReporterId(), r.getId()))
                    .toList();
            findings.add(new ImpactFinding(
                    ImpactSeverity.WARNING, "REPORT_TARGETING", targeting.size(), authors));
        }

        List<ReportEntity> authored =
                reportRepository.findByStatusAndReporterId(ReportStatus.OPEN, userId);
        if (!authored.isEmpty()) {
            // Une annonce ou un message n'est pas un compte : rien à nommer dans ces cas-là,
            // mais le signalement reste dans le décompte.
            List<ImpactFinding.AffectedParty> targets = authored.stream()
                    .filter(r -> r.getTargetType() == ReportTargetType.USER && r.getTargetId() != null)
                    .map(r -> new ImpactFinding.AffectedParty(r.getTargetId(), r.getId()))
                    .toList();
            findings.add(new ImpactFinding(
                    ImpactSeverity.WARNING, "REPORT_AUTHORED", authored.size(), targets));
        }

        return findings;
    }
}
