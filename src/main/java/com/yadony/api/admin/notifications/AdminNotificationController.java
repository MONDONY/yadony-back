package com.yadony.api.admin.notifications;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cloche du panel admin. Ouverte à tout administrateur authentifié : ce que chacun y voit est
 * filtré par ses propres permissions, jamais par une permission dédiée à la cloche.
 */
@RestController
@RequestMapping("/admin/notifications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminNotificationController {

    private static final Set<String> PERMISSION_NAMES =
            Arrays.stream(AdminPermission.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final AdminNotificationService service;

    public AdminNotificationController(AdminNotificationService service) {
        this.service = service;
    }

    @GetMapping("/feed")
    public ResponseEntity<AdminNotificationFeedResponse> feed(
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime before,
            Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        return ResponseEntity.ok(service.feed(adminId, permissions(authentication),
                before == null ? null : before.toInstant(), limit));
    }

    @GetMapping("/counters")
    public ResponseEntity<AdminNotificationCountersResponse> counters(Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        return ResponseEntity.ok(service.counters(adminId, permissions(authentication)));
    }

    @PostMapping("/mark-seen")
    public ResponseEntity<Void> markSeen(@RequestBody(required = false) MarkSeenRequest request,
                                         Authentication authentication) {
        UUID adminId = AdminPrincipal.requireAdminId(authentication);
        service.markSeen(adminId, request == null || request.upTo() == null ? null : request.upTo().toInstant());
        return ResponseEntity.noContent().build();
    }

    /** Permissions effectives de l'appelant, telles que résolues par le filtre d'authentification. */
    private static Set<AdminPermission> permissions(Authentication authentication) {
        Set<AdminPermission> permissions = EnumSet.noneOf(AdminPermission.class);
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (PERMISSION_NAMES.contains(name)) {
                permissions.add(AdminPermission.valueOf(name));
            }
        }
        return permissions;
    }
}
