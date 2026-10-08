package com.yadony.api.matching;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Génère les trajets d'une récurrence juste après le commit de sa création ou de sa
 * modification, encore dans la requête HTTP.
 *
 * <p>Générer dans la transaction de {@code create}/{@code update} faisait échouer
 * l'enregistrement de la récurrence elle-même : chaque trajet passe par
 * {@code AnnouncementService#createRecurringAnnouncement} (transactionnel, propagation
 * REQUIRED), et la moindre erreur d'une occurrence (limite PRO, KYC, doublon publié par le
 * scheduler) marquait la transaction englobante rollback-only, donc un 500 au commit
 * malgré le try/catch de la boucle.
 *
 * <p>{@code NOT_SUPPORTED} suspend la transaction terminée : chaque trajet a la sienne,
 * comme sur le chemin du scheduler. Une transaction REQUIRES_NEW par trajet n'était pas
 * possible avant le commit : la clé étrangère {@code source_recurrence_id} (V234) ne voit
 * pas une récurrence encore non commitée.
 */
@Component
class TripRecurrenceGenerationListener {

    private final TripRecurrenceService tripRecurrenceService;

    TripRecurrenceGenerationListener(TripRecurrenceService tripRecurrenceService) {
        this.tripRecurrenceService = tripRecurrenceService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onRecurrenceSaved(TripRecurrenceSavedEvent event) {
        tripRecurrenceService.generateForRecurrenceId(event.recurrenceId());
    }
}
