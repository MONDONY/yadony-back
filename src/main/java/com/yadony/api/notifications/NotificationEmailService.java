package com.yadony.api.notifications;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.emailotp.ResendEmailService;
import com.yadony.api.notifications.NotificationEmailPolicy.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * E-mails des notifications (plan de réduction du bruit, étape 5). Le texte est celui de la
 * notification, déjà dans la langue du destinataire ; le gabarit ajoute une ligne propre à
 * sa famille ({@link NotificationEmailPolicy.Kind}).
 *
 * <p>Rien ne part sans adresse (comptes créés par téléphone), ni quand l'utilisateur a coupé
 * la famille. Au-delà de {@link #DAILY_CAP} e-mails coupables dans la journée, les suivants
 * restent dans l'app : un reçu, une décision ou une démarche de compte n'est jamais plafonné.
 * Un échec d'envoi est seulement journalisé, la notification est déjà dans le feed.
 */
@Service
public class NotificationEmailService {

    private static final Logger log = LoggerFactory.getLogger(NotificationEmailService.class);

    static final int DAILY_CAP = 6;

    private final boolean enabled;
    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContactService;
    private final NotificationPrefsService prefsService;
    private final ResendEmailService emailService;
    private final MessagesResolver messagesResolver;
    private final Map<String, NotificationEmailLink> links;
    private final Clock clock;
    private final Cache<String, AtomicInteger> sentToday =
            Caffeine.newBuilder().maximumSize(200_000).expireAfterWrite(Duration.ofHours(26)).build();

    @Autowired
    public NotificationEmailService(@Value("${yadony.notifications.email.enabled:true}") boolean enabled,
                                    UserRepository userRepository,
                                    FirebaseContactService firebaseContactService,
                                    NotificationPrefsService prefsService,
                                    ResendEmailService emailService,
                                    MessagesResolver messagesResolver,
                                    ObjectProvider<NotificationEmailLink> emailLinks) {
        this(enabled, userRepository, firebaseContactService, prefsService, emailService,
                messagesResolver, emailLinks.orderedStream().toList(), Clock.systemUTC());
    }

    NotificationEmailService(boolean enabled, UserRepository userRepository,
                             FirebaseContactService firebaseContactService,
                             NotificationPrefsService prefsService, ResendEmailService emailService,
                             MessagesResolver messagesResolver, Clock clock) {
        this(enabled, userRepository, firebaseContactService, prefsService, emailService,
                messagesResolver, List.of(), clock);
    }

    NotificationEmailService(boolean enabled, UserRepository userRepository,
                             FirebaseContactService firebaseContactService,
                             NotificationPrefsService prefsService, ResendEmailService emailService,
                             MessagesResolver messagesResolver, List<NotificationEmailLink> emailLinks,
                             Clock clock) {
        this.enabled = enabled;
        this.userRepository = userRepository;
        this.firebaseContactService = firebaseContactService;
        this.prefsService = prefsService;
        this.emailService = emailService;
        this.messagesResolver = messagesResolver;
        this.links = emailLinks.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(NotificationEmailLink::type, l -> l));
        this.clock = clock;
    }

    /**
     * Le push peut-il se taire au profit de l'e-mail ? Vrai seulement pour un type de la
     * famille « e-mail à la place du push » dont l'e-mail partira vraiment.
     */
    public boolean replacesPush(UUID userId, String type, Map<String, String> data) {
        if (!NotificationEmailPolicy.replacesPush(type, data)) {
            return false;
        }
        try {
            return deliverable(userId, type, data).isPresent();
        } catch (RuntimeException e) {
            log.warn("[Mail] Remplacement du push {} impossible à décider : {}", type, e.getClass().getSimpleName());
            return false;
        }
    }

    /** Envoie l'e-mail de la notification si son type en a un et qu'il peut partir. */
    @Async
    public void send(UUID userId, String type, String title, String body, Map<String, String> data) {
        try {
            Optional<Target> target = deliverable(userId, type, data);
            if (target.isEmpty() || !withinDailyCap(userId, target.get().kind())) {
                return;
            }
            Optional<NotificationEmailLink.Cta> cta = ctaFor(userId, type);
            if (cta.isPresent()) {
                emailService.sendNotification(target.get().email(), target.get().kind(), type,
                        title, body, cta.get(), messagesResolver.forUser(userId));
            } else {
                emailService.sendNotification(target.get().email(), target.get().kind(), type,
                        title, body, NotificationOpenLink.urlFor(type, data), messagesResolver.forUser(userId));
            }
        } catch (RuntimeException e) {
            log.warn("[Mail] E-mail {} non envoyé à {} : {}", type, userId, e.getClass().getSimpleName());
        }
    }

    /**
     * Bouton propre au type, s'il en a un. Un lien qui échoue retombe sur le bouton par
     * défaut : le push est déjà remplacé, l'e-mail doit partir quoi qu'il arrive.
     */
    private Optional<NotificationEmailLink.Cta> ctaFor(UUID userId, String type) {
        NotificationEmailLink link = links.get(type);
        if (link == null) {
            return Optional.empty();
        }
        try {
            return link.ctaFor(userId);
        } catch (RuntimeException e) {
            log.warn("[Mail] Lien propre de {} indisponible : {}", type, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private record Target(String email, Kind kind) {}

    private Optional<Target> deliverable(UUID userId, String type, Map<String, String> data) {
        if (!enabled || userId == null) {
            return Optional.empty();
        }
        Optional<Kind> kind = NotificationEmailPolicy.kindOf(type, data);
        if (kind.isEmpty() || !prefsService.isEmailAllowed(userId, kind.get())) {
            return Optional.empty();
        }
        return userRepository.findById(userId)
                .map(user -> firebaseContactService.getContact(user.getFirebaseUid()).email())
                .filter(email -> email != null && !email.isBlank())
                .map(email -> new Target(email.trim(), kind.get()));
    }

    private boolean withinDailyCap(UUID userId, Kind kind) {
        if (kind.pref().isEmpty()) {
            return true;
        }
        String day = userId + "|" + LocalDate.now(clock);
        return sentToday.get(day, k -> new AtomicInteger()).incrementAndGet() <= DAILY_CAP;
    }
}
