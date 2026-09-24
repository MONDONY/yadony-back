package com.yadony.api.notifications;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Runs every 30 seconds. Finds critical notifications older than 60s with no ACK
 * and sends an SMS fallback. Idempotent: smsSentAt is set to prevent double-sends.
 */
@Component
public class SmsFallbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(SmsFallbackScheduler.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final SmsService smsService;
    private final FirebaseContactService firebaseContact;

    public SmsFallbackScheduler(NotificationRepository notificationRepository,
                                UserRepository userRepository,
                                SmsService smsService,
                                FirebaseContactService firebaseContact) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.smsService = smsService;
        this.firebaseContact = firebaseContact;
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void processPendingFallbacks() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(60);
        var pending = notificationRepository.findPendingSmsFallbacks(cutoff);

        if (pending.isEmpty()) return;

        log.debug("[SmsFallback] Processing {} pending fallback(s)", pending.size());

        // Les numéros vivent dans Firebase : on les résout en un lot avant la boucle,
        // sinon chaque notification en attente coûterait un aller-retour réseau, en
        // série et transaction ouverte.
        Map<UUID, UserEntity> usersById = userRepository
                .findAllById(pending.stream().map(NotificationEntity::getUserId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));
        Map<String, FirebaseContactService.Contact> contacts = firebaseContact.getContacts(
                usersById.values().stream().map(UserEntity::getFirebaseUid).toList());

        for (var notification : pending) {
            try {
                Optional.ofNullable(usersById.get(notification.getUserId())).ifPresentOrElse(user -> {
                    String phone = contacts
                            .getOrDefault(user.getFirebaseUid(), FirebaseContactService.Contact.EMPTY)
                            .phoneNumber();
                    if (phone != null && !phone.isBlank()) {
                        try {
                            smsService.send(phone, buildSmsText(notification));
                            log.info("[SmsFallback] SMS sent for notificationId={} userId={}",
                                    notification.getId(), notification.getUserId());
                        } catch (InvalidSmsRecipientException e) {
                            // Le transporteur refuse ce numéro : le rejouer toutes les 30 s
                            // ne changerait rien et facturerait un appel à chaque passage.
                            // Traitée comme « pas de numéro » : marquée, pas retentée.
                            log.warn("[SmsFallback] Recipient rejected by carrier (code {}) for notificationId={}, no retry",
                                    e.getCarrierErrorCode(), notification.getId());
                        }
                    } else {
                        log.warn("[SmsFallback] No phone number for userId={}, skipping",
                                notification.getUserId());
                    }
                    // Mark as handled regardless (no phone = no retry)
                    notification.markSmsSent(LocalDateTime.now(ZoneOffset.UTC));
                }, () -> {
                    log.warn("[SmsFallback] User not found for notificationId={}", notification.getId());
                    notification.markSmsSent(LocalDateTime.now(ZoneOffset.UTC));
                });
            } catch (Exception e) {
                log.error("[SmsFallback] Error for notificationId={}: {}", notification.getId(), e.getMessage());
            }
        }
    }

    private String buildSmsText(NotificationEntity n) {
        return "[Yadony] " + n.getTitle() + (n.getBody() != null ? " — " + n.getBody() : "");
    }
}