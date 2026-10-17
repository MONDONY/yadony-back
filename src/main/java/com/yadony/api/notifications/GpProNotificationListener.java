package com.yadony.api.notifications;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.emailotp.ResendEmailService;
import com.yadony.api.gppro.events.GpProDecisionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Annonce à l'utilisateur la décision de l'admin sur son badge GP Pro : push dans sa
 * langue, puis e-mail s'il a une adresse (le motif n'est que dans l'e-mail et l'écran
 * de l'app). Le nom commercial et les documents ne figurent jamais dans un message.
 * AFTER_COMMIT : une décision annulée n'annonce rien.
 */
@Component
public class GpProNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(GpProNotificationListener.class);

    private final NotificationDispatcher dispatcher;
    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContactService;
    private final ResendEmailService emailService;

    public GpProNotificationListener(NotificationDispatcher dispatcher, UserRepository userRepository,
                                     FirebaseContactService firebaseContactService,
                                     ResendEmailService emailService) {
        this.dispatcher = dispatcher;
        this.userRepository = userRepository;
        this.firebaseContactService = firebaseContactService;
        this.emailService = emailService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDecision(GpProDecisionEvent event) {
        Messages m = dispatcher.messagesFor(event.userId());
        NotificationText text = switch (event.kind()) {
            case APPROVED -> NotificationTexts.gpProApproved(m);
            case REJECTED -> NotificationTexts.gpProRejected(m);
            case REVOKED -> NotificationTexts.gpProRevoked(m);
        };
        // Types écrits en entier : NotificationPrefsClassificationTest les lit dans le source.
        String type = switch (event.kind()) {
            case APPROVED -> "GP_PRO_APPROVED";
            case REJECTED -> "GP_PRO_REJECTED";
            case REVOKED -> "GP_PRO_REVOKED";
        };
        dispatcher.notifyUser(event.userId(), text.title(), text.body(), Map.of("type", type));
        sendEmail(event, m);
    }

    private void sendEmail(GpProDecisionEvent event, Messages m) {
        try {
            String email = userRepository.findById(event.userId())
                    .map(u -> firebaseContactService.getContact(u.getFirebaseUid()).email())
                    .orElse(null);
            if (email == null || email.isBlank()) {
                return;
            }
            emailService.sendGpProDecision(email, event.kind(), event.reason(), m);
        } catch (RuntimeException e) {
            log.warn("E-mail de décision GP Pro non envoyé pour l'utilisateur {} : {}", event.userId(), e.getMessage());
        }
    }
}
