package com.yadony.api.matching;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementInsightsResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Audience d'un trajet : l'app signale chaque ouverture, le voyageur lit le total.
 * Les invités n'atteignent pas ces routes (règle finale de SecurityConfig) : sans
 * compte, une consultation ne peut pas être attribuée à une personne.
 */
@RestController
@RequestMapping("/announcements/{id}")
public class AnnouncementInsightController {

    private final AnnouncementViewService service;
    private final UserRepository userRepository;

    public AnnouncementInsightController(AnnouncementViewService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    /** 204 dans tous les cas où la vue n'est pas comptée (voyageur, trajet hors ligne, déjà vu). */
    @PostMapping("/views")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordView(@PathVariable UUID id) {
        service.recordView(requireUserId(), id);
    }

    @GetMapping("/insights")
    @PreAuthorize("hasRole('TRAVELER')")
    public AnnouncementInsightsResponse insights(@PathVariable UUID id) {
        return service.getInsights(requireUserId(), id);
    }

    private UUID requireUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "Un token Firebase valide est requis");
        }
        return userRepository.findByFirebaseUid((String) auth.getPrincipal())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"))
                .getId();
    }
}
