package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.dac7.events.Dac7TaxInfoRequestedEvent;
import com.yadony.api.dac7.events.Dac7TaxStatementEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * DAC7 (lot 3b) : notifications de la demande du numéro fiscal ({@code TAX_INFO_REQUEST}) et du récapitulatif annuel
 * ({@code TAX_STATEMENT}), qui ouvrent toutes deux l'écran {@code /profile/tax-info}. AFTER_COMMIT : une réservation
 * annulée ne prévient personne ; un envoi raté n'est pas rejoué (la ligne du registre est déjà posée), le WARN le
 * signale. Aucune donnée fiscale dans les textes ni dans les données de la notification.
 */
@Component
public class Dac7NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(Dac7NotificationListener.class);

    static final String REQUEST_TYPE = "TAX_INFO_REQUEST";
    static final String STATEMENT_TYPE = "TAX_STATEMENT";
    static final String ROUTE = "/profile/tax-info";

    private final NotificationDispatcher dispatcher;

    public Dac7NotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaxInfoRequested(Dac7TaxInfoRequestedEvent event) {
        try {
            Messages m = dispatcher.messagesFor(event.userId());
            boolean reminder = !"REQUEST".equals(event.action());
            NotificationText text = reminder ? NotificationTexts.taxInfoReminder(m) : NotificationTexts.taxInfoRequest(m);
            dispatcher.notifyUser(event.userId(), text.title(), text.body(), Map.of("type", REQUEST_TYPE,
                    "route", ROUTE, "reminder", String.valueOf(reminder), "step", event.action()));
        } catch (RuntimeException e) {
            log.warn("Demande DAC7 {} non envoyée (utilisateur {}) : {}", event.action(), event.userId(),
                    e.getClass().getSimpleName());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaxStatement(Dac7TaxStatementEvent event) {
        try {
            Messages m = dispatcher.messagesFor(event.userId());
            NotificationText text = NotificationTexts.taxStatement(m, event.year());
            List<long[]> quarters = event.quarters().stream()
                    .map(q -> new long[]{q.netEur(), q.operations(), q.feesEur()}).toList();
            String detail = NotificationTexts.taxStatementDetail(m, event.year(), quarters);
            dispatcher.notifyUser(event.userId(), text.title(), detail, Map.of("type", STATEMENT_TYPE,
                    "route", ROUTE, "year", String.valueOf(event.year())));
        } catch (RuntimeException e) {
            log.warn("Récapitulatif DAC7 {} non envoyé (utilisateur {}) : {}", event.year(), event.userId(),
                    e.getClass().getSimpleName());
        }
    }
}
