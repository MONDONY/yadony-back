package com.yadony.api.subscriptions;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.common.StorageService;
import com.yadony.api.subscriptions.dto.SubscriberResponse;
import com.yadony.api.subscriptions.dto.SubscriptionItemResponse;
import com.yadony.api.subscriptions.dto.SubscriptionStatusResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SubscriptionService {

    private final TravelerSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final BlockVisibility blockVisibility;

    public SubscriptionService(TravelerSubscriptionRepository subscriptionRepository,
                               UserRepository userRepository,
                               StorageService storageService,
                               BlockVisibility blockVisibility) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.blockVisibility = blockVisibility;
    }

    private UUID senderId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
            .orElseThrow(() -> new YadonyNotFoundException("Sender not found"))
            .getId();
    }

    /**
     * Le blocage est silencieux (404, jamais 403) : un compte masqué se comporte
     * exactement comme un compte inexistant, sinon le blocage serait détectable.
     */
    @Transactional
    public void subscribe(String firebaseUid, UUID travelerId) {
        UUID sid = senderId(firebaseUid);
        blockVisibility.assertVisible(sid, travelerId);
        userRepository.findById(travelerId)
            .orElseThrow(() -> new YadonyNotFoundException("Traveler", travelerId));

        var existing = subscriptionRepository.findBySenderIdAndTravelerIdIncludingDeleted(sid, travelerId);
        if (existing.isPresent()) {
            TravelerSubscriptionEntity sub = existing.get();
            if (sub.getDeletedAt() != null) {   // réactiver un abonnement soft-deleted
                sub.setDeletedAt(null);
                sub.setHasNew(false);           // indicateur "nouveau" obsolète après désabonnement
                subscriptionRepository.save(sub);
            }
            return;
        }
        TravelerSubscriptionEntity sub = new TravelerSubscriptionEntity();
        sub.setSenderId(sid);
        sub.setTravelerId(travelerId);
        try {
            subscriptionRepository.save(sub);
        } catch (DataIntegrityViolationException e) {
            // Double-tap concurrent : la contrainte UNIQUE(sender_id, traveler_id) a déjà
            // créé la ligne — abonnement idempotent, on ignore.
        }
    }

    @Transactional
    public void unsubscribe(String firebaseUid, UUID travelerId) {
        UUID sid = senderId(firebaseUid);
        subscriptionRepository.findBySenderIdAndTravelerId(sid, travelerId).ifPresent(sub -> {
            sub.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
            subscriptionRepository.save(sub);
        });
    }

    @Transactional
    public void setPush(String firebaseUid, UUID travelerId, boolean enabled) {
        UUID sid = senderId(firebaseUid);
        blockVisibility.assertVisible(sid, travelerId);
        TravelerSubscriptionEntity sub = subscriptionRepository.findBySenderIdAndTravelerId(sid, travelerId)
            .orElseThrow(() -> new YadonyNotFoundException("Subscription not found"));
        sub.setPushEnabled(enabled);
        subscriptionRepository.save(sub);
    }

    @Transactional
    public void markSeen(String firebaseUid, UUID travelerId) {
        UUID sid = senderId(firebaseUid);
        subscriptionRepository.findBySenderIdAndTravelerId(sid, travelerId).ifPresent(sub -> {
            sub.setHasNew(false);
            subscriptionRepository.save(sub);
        });
    }

    /**
     * Retire l'indicateur « nouveau » de tous les abonnements de l'expéditeur.
     *
     * <p>Existe parce que l'écran « Mes abonnements » offre une action unique :
     * marquer un à un aurait produit autant de requêtes que d'abonnements.</p>
     */
    @Transactional
    public void markAllSeen(String firebaseUid) {
        subscriptionRepository.markAllSeenBySenderId(senderId(firebaseUid));
    }

    @Transactional(readOnly = true)
    public SubscriptionStatusResponse getStatus(String firebaseUid, UUID travelerId) {
        UUID sid = senderId(firebaseUid);
        blockVisibility.assertVisible(sid, travelerId);
        return subscriptionRepository.findBySenderIdAndTravelerId(sid, travelerId)
            .map(s -> new SubscriptionStatusResponse(true, s.isPushEnabled()))
            .orElse(new SubscriptionStatusResponse(false, false));
    }

    /**
     * Les abonnements vers un compte bloqué sont masqués, pas résiliés : ils
     * réapparaissent au déblocage, comme partout ailleurs dans l'application.
     * Filtrage en mémoire : la liste n'est pas paginée.
     */
    @Transactional(readOnly = true)
    public List<SubscriptionItemResponse> getMySubscriptions(String firebaseUid) {
        UUID sid = senderId(firebaseUid);
        Set<UUID> hidden = blockVisibility.hiddenUserIdsFor(sid);
        return subscriptionRepository.findEnrichedBySenderId(sid).stream()
            .filter(r -> !hidden.contains((UUID) r[0]))
            .map(this::mapRow)
            .toList();
    }

    /** Expéditeurs abonnés au voyageur courant (côté voyageur). */
    @Transactional(readOnly = true)
    public List<SubscriberResponse> getMySubscribers(String firebaseUid) {
        UUID travelerId = senderId(firebaseUid); // résout l'utilisateur courant
        Set<UUID> hidden = blockVisibility.hiddenUserIdsFor(travelerId);
        return subscriptionRepository.findAllByTravelerId(travelerId).stream()
            .filter(sub -> !hidden.contains(sub.getSenderId()))
            .map(sub -> {
                String name = userRepository.findById(sub.getSenderId())
                    .map(this::buildSubscriberName)
                    .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
                return new SubscriberResponse(sub.getSenderId(), name, sub.getCreatedAt());
            })
            .toList();
    }

    /** Délègue à {@link UserEntity#publicDisplayName()} : repli sur le username du compte. */
    private String buildSubscriberName(UserEntity u) {
        return u.publicDisplayName();
    }

    private SubscriptionItemResponse mapRow(Object[] r) {
        SubscriptionItemResponse.LastAnnouncement last = null;
        if (r[8] != null) {
            LocalDateTime published = r[14] instanceof java.sql.Timestamp ts
                ? ts.toLocalDateTime()
                : ((java.time.Instant) r[14]).atZone(ZoneOffset.UTC).toLocalDateTime();
            // La colonne remonte en java.sql.Date sous PostgreSQL, en LocalDate
            // sous H2 : les deux formes doivent être acceptées.
            java.time.LocalDate departure = switch (r[13]) {
                case java.sql.Date d -> d.toLocalDate();
                case java.time.LocalDate d -> d;
                case null, default -> null;
            };
            // Repli sur EUR : la colonne est NOT NULL en base, mais une annonce
            // antérieure au multidevise pourrait remonter nulle d'un jeu de test.
            String currency = r[12] != null ? (String) r[12] : "EUR";
            last = new SubscriptionItemResponse.LastAnnouncement(
                (UUID) r[8], (String) r[9], (String) r[10],
                (BigDecimal) r[11],
                currency,
                departure,
                published
            );
        }
        return new SubscriptionItemResponse(
            (UUID) r[0],
            (String) r[1],
            storageService.avatarUrl((String) r[2]),
            (Boolean) r[3],
            (BigDecimal) r[4],
            ((Number) r[5]).longValue(),
            (Boolean) r[6],
            (Boolean) r[7],
            last
        );
    }
}
