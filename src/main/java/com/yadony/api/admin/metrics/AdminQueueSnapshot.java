package com.yadony.api.admin.metrics;

import java.util.Map;
import java.util.UUID;

/**
 * Photographie des files à traiter du panel, partagée par la vue d'ensemble et les compteurs
 * du menu. Indépendante de l'administrateur qui la lit : le filtrage par permission et la part
 * « assignés à moi » du support se font après coup, ce qui permet de la mettre en cache une
 * seule fois pour tous.
 *
 * @param supportUnassigned tickets non résolus sans administrateur assigné
 * @param supportByAdmin    tickets non résolus par administrateur assigné
 */
public record AdminQueueSnapshot(
        long openReports,
        long supportUnassigned,
        Map<UUID, Long> supportByAdmin,
        long openDisputes,
        long pendingNoShows,
        long kycInReview,
        long heldPayouts,
        long pendingWalletRefunds,
        long pendingGdpr,
        long unresolvedAlerts
) {
    public AdminQueueSnapshot {
        supportByAdmin = supportByAdmin == null ? Map.of() : Map.copyOf(supportByAdmin);
    }

    /** Tickets à traiter pour cet administrateur : non assignés, ou assignés à lui. */
    public long supportFor(UUID adminId) {
        return supportUnassigned + supportByAdmin.getOrDefault(adminId, 0L);
    }
}
