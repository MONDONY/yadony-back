package com.yadony.api.notifications;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.PageResponse;
import com.yadony.api.notifications.dto.AnnouncementsSummaryDTO;
import com.yadony.api.notifications.dto.FeedItemDTO;
import com.yadony.api.notifications.dto.NotificationDTO;
import com.yadony.api.notifications.dto.NotificationDetailDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationFeedService feedService;
    private final NotificationPrefsService notificationPrefsService;

    public NotificationController(NotificationService notificationService,
                                  NotificationFeedService feedService,
                                  NotificationPrefsService notificationPrefsService) {
        this.notificationService = notificationService;
        this.feedService = feedService;
        this.notificationPrefsService = notificationPrefsService;
    }

    /** Liste historique, toutes catégories confondues, sans agrégation. Reste servie pour l'app actuelle. */
    @GetMapping
    public ResponseEntity<PageResponse<NotificationDTO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(notificationService.list(requireUid(), page, size));
    }

    // ── Sheet refondu : feed agrégé, boîte annonces ──────────────────────────

    /** Le feed : tout sauf les annonces plateforme, les groupes non lus repliés à partir de trois. */
    @GetMapping("/feed")
    public ResponseEntity<PageResponse<FeedItemDTO>> feed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(feedService.feed(requireUid(), page, size));
    }

    /** La boîte « Annonces yadony » : uniquement les annonces plateforme. */
    @GetMapping("/annonces")
    public ResponseEntity<PageResponse<NotificationDTO>> announcements(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(feedService.announcements(requireUid(), page, size));
    }

    /** La carte en tête de sheet : compteur de non-lus et dernière annonce. */
    @GetMapping("/annonces/summary")
    public ResponseEntity<AnnouncementsSummaryDTO> announcementsSummary() {
        return ResponseEntity.ok(feedService.announcementsSummary(requireUid()));
    }

    /** Lire une ligne agrégée : toutes les non-lues du groupe d'un coup. */
    @PatchMapping("/groups/read")
    public ResponseEntity<Map<String, Integer>> markGroupRead(@RequestParam String groupKey) {
        return ResponseEntity.ok(Map.of("count", feedService.markGroupRead(requireUid(), groupKey)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        return ResponseEntity.ok(Map.of("count", notificationService.countUnread(requireUid())));
    }

    /**
     * Une notification seule, avec {@code fullBody}. C'est l'écran de détail
     * générique qui l'appelle, pour une ligne sans deeplink (une annonce
     * plateforme). La liste, elle, ne sert jamais le texte complet.
     */
    @GetMapping("/{id}")
    public ResponseEntity<NotificationDetailDTO> detail(@PathVariable UUID id) {
        return ResponseEntity.ok(notificationService.detail(requireUid(), id));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        notificationService.markRead(requireUid(), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        notificationService.markAllRead(requireUid());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        notificationService.softDelete(requireUid(), id);
        return ResponseEntity.noContent().build();
    }

    // Story 8.3 — Flutter sends ACK on notification receipt to cancel SMS fallback
    @PostMapping("/{id}/ack")
    public ResponseEntity<Void> ack(@PathVariable UUID id) {
        notificationService.ack(requireUid(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    public ResponseEntity<NotificationPrefsDto> getPreferences() {
        return ResponseEntity.ok(notificationPrefsService.getPrefs(requireUid()));
    }

    @PutMapping("/preferences")
    public ResponseEntity<Void> updatePreferences(@RequestBody NotificationPrefsDto dto) {
        notificationPrefsService.upsert(requireUid(), dto);
        return ResponseEntity.noContent().build();
    }

    // Cloche « Colis sur mes trajets » : toggle de la notif temps réel match colis.
    @GetMapping("/package-match-alert")
    public ResponseEntity<PackageMatchAlertDto> getPackageMatchAlert() {
        return ResponseEntity.ok(
                new PackageMatchAlertDto(notificationPrefsService.getPackageMatchAlert(requireUid())));
    }

    @PutMapping("/package-match-alert")
    public ResponseEntity<Void> updatePackageMatchAlert(@RequestBody PackageMatchAlertDto dto) {
        notificationPrefsService.setPackageMatchAlert(requireUid(), dto.enabled());
        return ResponseEntity.noContent().build();
    }

    private String requireUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return (String) auth.getPrincipal();
    }
}