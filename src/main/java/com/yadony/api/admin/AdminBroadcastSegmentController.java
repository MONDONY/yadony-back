package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.broadcast.segment.BroadcastSegmentService;
import com.yadony.api.admin.dto.BroadcastSegmentRequest;
import com.yadony.api.admin.dto.BroadcastSegmentResponse;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Segments d'audience enregistrés. Réservé à NOTIFICATION_SEND (pas SUPPORT).
 *
 * <p>⚠️ Chaque méthode re-déclare l'expression complète : une {@code @PreAuthorize} de
 * méthode remplace celle de la classe.
 */
@RestController
@RequestMapping("/admin/broadcast-segments")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
public class AdminBroadcastSegmentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final BroadcastSegmentService service;

    public AdminBroadcastSegmentController(BroadcastSegmentService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
    @GetMapping
    public Page<BroadcastSegmentResponse> list(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return service.list(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
    @PostMapping
    public ResponseEntity<BroadcastSegmentResponse> create(@RequestBody @Valid BroadcastSegmentRequest request,
                                                           Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, adminId(authentication)));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
    @GetMapping("/{id}")
    public BroadcastSegmentResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
    @PutMapping("/{id}")
    public BroadcastSegmentResponse update(@PathVariable UUID id,
                                           @RequestBody @Valid BroadcastSegmentRequest request,
                                           Authentication authentication) {
        return service.update(id, request, adminId(authentication));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        service.delete(id, adminId(authentication));
        return ResponseEntity.noContent().build();
    }

    private UUID adminId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "admin-principal-required", "problem.admin.admin-principal-required");
    }
}
