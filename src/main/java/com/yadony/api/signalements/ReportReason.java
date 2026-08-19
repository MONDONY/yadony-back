package com.yadony.api.signalements;

import java.util.EnumSet;
import java.util.Set;

/**
 * Motifs catalogués d'un signalement, avec les types de cible auxquels ils s'appliquent.
 *
 * <p>Un champ libre ne permettait ni filtrage ni agrégation côté modération, et proposait
 * les mêmes motifs pour signaler un utilisateur ou un bug de l'application. Le catalogue
 * est vérifié contre le {@link ReportTargetType} à la création — voir
 * {@link ReportService#createReport}.
 */
public enum ReportReason {

    HARASSMENT("Harcèlement ou comportement abusif", ReportTargetType.USER),
    FAKE_PROFILE("Faux profil", ReportTargetType.USER),
    SCAM_ATTEMPT("Tentative d'arnaque",
            ReportTargetType.USER, ReportTargetType.ANNOUNCEMENT, ReportTargetType.BID),
    PROHIBITED_ITEM("Objet interdit au transport",
            ReportTargetType.ANNOUNCEMENT, ReportTargetType.BID),
    FALSE_INFORMATION("Informations fausses ou trompeuses",
            ReportTargetType.ANNOUNCEMENT, ReportTargetType.BID),
    INAPPROPRIATE_CONTENT("Contenu inapproprié",
            ReportTargetType.USER, ReportTargetType.ANNOUNCEMENT,
            ReportTargetType.MESSAGE, ReportTargetType.RATING),
    SPAM("Spam", ReportTargetType.MESSAGE, ReportTargetType.RATING),
    PAYMENT_ISSUE("Problème de paiement", ReportTargetType.APP, ReportTargetType.BID),
    APP_BUG("Bug de l'application", ReportTargetType.APP),
    OTHER("Autre",
            ReportTargetType.USER, ReportTargetType.ANNOUNCEMENT, ReportTargetType.BID,
            ReportTargetType.MESSAGE, ReportTargetType.RATING, ReportTargetType.APP);

    private final String label;
    private final Set<ReportTargetType> applicableTargets;

    ReportReason(String label, ReportTargetType... applicableTargets) {
        this.label = label;
        this.applicableTargets = EnumSet.copyOf(Set.of(applicableTargets));
    }

    public String label() {
        return label;
    }

    public boolean appliesTo(ReportTargetType targetType) {
        return applicableTargets.contains(targetType);
    }
}
