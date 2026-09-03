package com.yadony.api.notifications;

import com.yadony.api.common.PageResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.notifications.dto.AnnouncementsSummaryDTO;
import com.yadony.api.notifications.dto.FeedItemDTO;
import com.yadony.api.notifications.dto.NotificationDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Le feed et la boîte annonces du sheet de notifications.
 *
 * <p>L'agrégation est calculée à la lecture, pas matérialisée à l'écriture :
 * le volume par utilisateur est faible, et la règle (« à partir de trois
 * non-lues de même clé, une seule ligne ») change de valeur dès qu'une ligne
 * est lue, ce qu'une colonne figée ne suivrait pas.
 *
 * <p>Une ligne agrégée se place dans la page où tombe la date de sa
 * notification la plus récente ; une page peut donc dépasser {@code size} de
 * quelques lignes, mais chaque agrégat apparaît exactement une fois sur
 * l'ensemble des pages.
 */
@Service
@Transactional(readOnly = true)
public class NotificationFeedService {

    /** JPQL refuse une liste vide dans {@code NOT IN} : cette clé n'existe jamais en base. */
    static final String NO_COLLAPSED_KEY = "-";

    private final NotificationRepository repository;
    private final NotificationService notificationService;

    public NotificationFeedService(NotificationRepository repository, NotificationService notificationService) {
        this.repository = repository;
        this.notificationService = notificationService;
    }

    public PageResponse<FeedItemDTO> feed(String firebaseUid, int page, int size) {
        UUID userId = notificationService.resolveUserId(firebaseUid);
        Map<String, List<NotificationEntity>> collapsed = collapsedGroups(userId);
        Collection<String> keys = collapsed.isEmpty() ? List.of(NO_COLLAPSED_KEY) : collapsed.keySet();

        Page<NotificationEntity> rows = repository.findFeed(
                userId, NotificationCategory.ANNONCE, keys, PageRequest.of(page, size));
        List<FeedItemDTO> items = new ArrayList<>(rows.map(FeedItemDTO::single).getContent());

        // Bornes de la page : la dernière ligne de la page précédente (haut) et la
        // dernière ligne de celle-ci (bas). Un agrégat tombe dans la page dont les
        // bornes encadrent sa date ; nul = pas de borne (première ou dernière page).
        LocalDateTime top = null;
        if (!collapsed.isEmpty() && page > 0) {
            top = repository.findFeed(userId, NotificationCategory.ANNONCE, keys, PageRequest.of(page * size - 1, 1))
                    .getContent().stream().findFirst().map(NotificationEntity::getCreatedAt).orElse(null);
        }
        LocalDateTime bottom = rows.isLast() || items.isEmpty() ? null : items.get(items.size() - 1).createdAt();
        boolean pageExists = page == 0 || !items.isEmpty(); // au-delà de la fin, rien ne s'ajoute
        for (var group : collapsed.entrySet()) {
            LocalDateTime at = group.getValue().get(0).getCreatedAt();
            boolean belowTop = top == null || !at.isAfter(top);
            boolean aboveBottom = bottom == null || at.isAfter(bottom);
            if (pageExists && belowTop && aboveBottom) {
                items.add(FeedItemDTO.aggregate(group.getKey(), group.getValue()));
            }
        }
        items.sort(Comparator.comparing(FeedItemDTO::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));

        return new PageResponse<>(items, rows.getNumber(), rows.getSize(),
                rows.getTotalElements() + collapsed.size(), rows.getTotalPages(), rows.isLast());
    }

    /** Les groupes repliés : clé → non-lues de la plus récente à la plus ancienne, à partir de trois. */
    Map<String, List<NotificationEntity>> collapsedGroups(UUID userId) {
        Map<String, List<NotificationEntity>> byKey = new LinkedHashMap<>();
        for (NotificationEntity n : repository.findByUserIdAndReadAtIsNullAndGroupKeyIsNotNullOrderByCreatedAtDesc(userId)) {
            if (n.getCategory() == NotificationCategory.ANNONCE) continue;
            byKey.computeIfAbsent(n.getGroupKey(), k -> new ArrayList<>()).add(n);
        }
        byKey.values().removeIf(list -> list.size() < NotificationAggregate.MIN_COUNT);
        return byKey;
    }

    public PageResponse<NotificationDTO> announcements(String firebaseUid, int page, int size) {
        UUID userId = notificationService.resolveUserId(firebaseUid);
        return PageResponse.from(repository
                .findByUserIdAndCategoryOrderByCreatedAtDesc(userId, NotificationCategory.ANNONCE, PageRequest.of(page, size))
                .map(NotificationDTO::from));
    }

    public AnnouncementsSummaryDTO announcementsSummary(String firebaseUid) {
        UUID userId = notificationService.resolveUserId(firebaseUid);
        return AnnouncementsSummaryDTO.of(
                repository.countByUserIdAndCategoryAndReadAtIsNull(userId, NotificationCategory.ANNONCE),
                repository.findFirstByUserIdAndCategoryOrderByCreatedAtDesc(userId, NotificationCategory.ANNONCE));
    }

    /**
     * Lire une ligne agrégée : toutes les non-lues du groupe passent lues d'un
     * coup. Une clé {@code notif:{id}} (ligne seule) revient à lire cette
     * notification. Renvoie le nombre de lignes passées lues.
     */
    @Transactional
    public int markGroupRead(String firebaseUid, String groupKey) {
        if (groupKey == null || groupKey.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "validation", "Validation Error",
                    "groupKey est requis");
        }
        if (groupKey.startsWith("notif:")) {
            notificationService.markRead(firebaseUid, parseId(groupKey.substring("notif:".length())));
            return 1;
        }
        UUID userId = notificationService.resolveUserId(firebaseUid);
        return repository.markGroupRead(userId, groupKey, LocalDateTime.now(ZoneOffset.UTC));
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "validation", "Validation Error",
                    "groupKey invalide");
        }
    }
}
