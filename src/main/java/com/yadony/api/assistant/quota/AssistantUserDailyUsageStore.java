package com.yadony.api.assistant.quota;

import java.time.LocalDate;

/** Questions du jour (UTC) par utilisateur, persistées : elles survivent aux redémarrages. */
public interface AssistantUserDailyUsageStore {

    /** Valeur rendue par {@link #tryConsume} quand le quota du jour est atteint. */
    int REFUSED = -1;

    /**
     * Compte une question pour ce jour, de façon atomique : sous concurrence, jamais plus de {@code limit}
     * questions comptées. Une question refusée n'est pas comptée.
     *
     * @return le nombre de questions du jour, celle-ci comprise, ou {@link #REFUSED}
     */
    int tryConsume(String uid, LocalDate day, int limit);
}
