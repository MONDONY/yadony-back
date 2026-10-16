package com.yadony.api.maintenance;

import java.time.Instant;
import java.util.Set;

/**
 * État lu à chaque requête par {@link MaintenanceFilter} : immuable et mis en cache.
 *
 * @param exemptFirebaseUids UID Firebase des comptes qui passent malgré la maintenance
 */
public record MaintenanceSnapshot(boolean enabled, String message, Instant estimatedEndAt,
                                  Instant startedAt, Set<String> exemptFirebaseUids) {

    public static final MaintenanceSnapshot OFF = new MaintenanceSnapshot(false, null, null, null, Set.of());

    public boolean isExempt(String firebaseUid) {
        return firebaseUid != null && exemptFirebaseUids.contains(firebaseUid);
    }
}
