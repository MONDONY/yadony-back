package com.yadony.api.signalements;

import java.util.Locale;
import java.util.Map;

/**
 * Conversion des motifs libres d'un signalement de demande d'envoi
 * ({@code package_request_reports.reason}, code court choisi dans l'app) vers le catalogue
 * {@link ReportReason} de la boîte générique.
 *
 * <p>Seul endroit Java de cette table. La migration V267 en porte le miroir SQL (CASE) pour
 * recopier les signalements existants : toute modification ici doit y être répercutée par
 * une nouvelle migration. Un motif inconnu ne doit jamais atteindre la colonne
 * {@code reports.reason} (CHECK {@code chk_reports_reason}, et un motif hors enum a déjà mis
 * la liste admin en 500, cf. V262) : il devient {@link ReportReason#OTHER} et son texte
 * d'origine passe en tête de la description, au même format que V262.
 */
public final class PackageRequestReportReasons {

    /**
     * Codes envoyés par l'app (fiche publique d'une demande : PROHIBITED, SCAM,
     * INAPPROPRIATE, OTHER), plus les codes du catalogue applicables à une demande.
     */
    private static final Map<String, ReportReason> TABLE = Map.of(
            "PROHIBITED", ReportReason.PROHIBITED_ITEM,
            "SCAM", ReportReason.SCAM_ATTEMPT,
            "INAPPROPRIATE", ReportReason.INAPPROPRIATE_CONTENT,
            "OTHER", ReportReason.OTHER,
            "PROHIBITED_ITEM", ReportReason.PROHIBITED_ITEM,
            "SCAM_ATTEMPT", ReportReason.SCAM_ATTEMPT,
            "FALSE_INFORMATION", ReportReason.FALSE_INFORMATION,
            "INAPPROPRIATE_CONTENT", ReportReason.INAPPROPRIATE_CONTENT
    );

    private PackageRequestReportReasons() {
    }

    /** Motif catalogué et description à écrire dans {@code reports}. */
    public record Converted(ReportReason reason, String description) {
    }

    public static Converted convert(String rawReason, String details) {
        if (rawReason == null || rawReason.isBlank()) {
            return new Converted(ReportReason.OTHER, details);
        }
        ReportReason known = TABLE.get(rawReason.trim().toUpperCase(Locale.ROOT));
        if (known != null) {
            return new Converted(known, details);
        }
        String prefix = "[Motif d'origine : " + rawReason + "]";
        String description = details == null || details.isBlank() ? prefix : prefix + " " + details;
        return new Converted(ReportReason.OTHER, description);
    }
}
