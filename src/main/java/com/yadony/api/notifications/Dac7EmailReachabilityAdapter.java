package com.yadony.api.notifications;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.dac7.Dac7EmailReachabilityPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Réalise {@link Dac7EmailReachabilityPort} avec la même source que {@code NotificationEmailService} : l'adresse du
 * contact Firebase du compte, et le même interrupteur ({@code yadony.notifications.email.enabled}, variable
 * {@code YADONY_NOTIFICATIONS_EMAIL_ENABLED}) : e-mails coupés, personne n'est joignable (relecture n° 3, finding 5 :
 * sinon les récapitulatifs seraient réservés et comptés envoyés sans qu'aucun e-mail ne parte). Aucune adresse n'est
 * rendue ni journalisée, seulement oui ou non.
 */
@Component
public class Dac7EmailReachabilityAdapter implements Dac7EmailReachabilityPort {

    private static final Logger log = LoggerFactory.getLogger(Dac7EmailReachabilityAdapter.class);

    private final UserRepository users;
    private final FirebaseContactService contacts;
    private final boolean emailEnabled;

    public Dac7EmailReachabilityAdapter(UserRepository users, FirebaseContactService contacts,
                                        @Value("${yadony.notifications.email.enabled:true}") boolean emailEnabled) {
        this.users = users;
        this.contacts = contacts;
        this.emailEnabled = emailEnabled;
    }

    @Override
    public boolean hasEmail(UUID userId) {
        if (!emailEnabled) {
            return false;
        }
        try {
            return users.findById(userId)
                    .map(user -> contacts.getContact(user.getFirebaseUid()).email())
                    .filter(email -> email != null && !email.isBlank())
                    .isPresent();
        } catch (RuntimeException e) {
            log.warn("Adresse e-mail illisible pour le récapitulatif DAC7 (utilisateur {}) : {}", userId,
                    e.getClass().getSimpleName());
            return false;
        }
    }
}
