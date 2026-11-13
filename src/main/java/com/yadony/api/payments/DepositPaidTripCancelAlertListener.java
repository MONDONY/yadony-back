package com.yadony.api.payments;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.cancellation.events.TripCancelRefusedDepositPaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Alerte l'équipe quand un voyageur tente d'annuler un trajet qui porte des colis déjà payés au transporteur à leur
 * dépôt (annulation refusée en 409 {@code deposit-paid-no-refund}) : colis déposés, transporteur payé, expéditeurs à
 * contacter. Une alerte ouverte par trajet ({@code raiseOnce}).
 *
 * <p>Écouteur SYNCHRONE ({@link EventListener}) et non {@code AFTER_COMMIT} : l'événement est publié juste avant le
 * refus, dans une transaction annulée. {@link AdminAlertEscalator#raiseOnce} écrit dans sa propre transaction
 * ({@code REQUIRES_NEW}), commitée malgré le refus. Un échec de l'alerte ne change jamais la réponse (409).
 */
@Component
public class DepositPaidTripCancelAlertListener {

    private static final Logger log = LoggerFactory.getLogger(DepositPaidTripCancelAlertListener.class);

    /** Préfixe (22) + UUID du trajet (36) = 58, sous la limite de 60 de {@code admin_alerts.type}. */
    public static final String ALERT_PREFIX = "TRIP_CANCEL_DEPOSITED_";

    private final AdminAlertEscalator alerts;

    public DepositPaidTripCancelAlertListener(AdminAlertEscalator alerts) {
        this.alerts = alerts;
    }

    @EventListener
    public void onTripCancelRefused(TripCancelRefusedDepositPaidEvent event) {
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("announcementId", event.announcementId().toString());
            context.put("travelerId", String.valueOf(event.travelerId()));
            context.put("paidBidIds", event.paidBidIds().stream().map(Object::toString).toList());
            alerts.raiseOnce(ALERT_PREFIX + event.announcementId(),
                    "Annulation refusée du trajet " + event.announcementId() + " : " + event.paidBidIds().size()
                            + " colis déjà déposés et payés au transporteur. Contacter le voyageur et les expéditeurs.",
                    context);
        } catch (RuntimeException e) {
            log.error("Alerte {}{} non levée", ALERT_PREFIX, event.announcementId(), e);
        }
    }
}
