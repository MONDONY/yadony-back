package com.yadony.api.common.deletion;

import java.util.List;
import java.util.UUID;

/**
 * Un constat d'impact rapporté par un package.
 *
 * <p>Le libellé n'est pas porté ici : {@code code} est stable et le back-office le traduit.
 * Les contreparties sont des identifiants nus — résoudre un nom demanderait d'injecter
 * {@code UserRepository}, qui vit dans {@code auth}, dans chacun des packages contributeurs.
 * L'agrégateur d'{@code admin} les résout en un seul batch.
 */
public record ImpactFinding(
        ImpactSeverity severity,
        String code,
        int count,
        List<AffectedParty> affectedParties
) {
    public record AffectedParty(UUID userId, UUID relatedEntityId) {}

    /** Constat sans contrepartie identifiable — un solde, un décompte. */
    public static ImpactFinding plain(ImpactSeverity severity, String code, int count) {
        return new ImpactFinding(severity, code, count, List.of());
    }

    /** Constat dont le nombre se déduit des contreparties listées. */
    public static ImpactFinding of(ImpactSeverity severity, String code, List<AffectedParty> parties) {
        return new ImpactFinding(severity, code, parties.size(), List.copyOf(parties));
    }
}
