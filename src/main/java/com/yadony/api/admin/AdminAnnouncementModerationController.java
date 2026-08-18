package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminAnnouncementListItemResponse;
import com.yadony.api.admin.dto.RemoveAnnouncementRequest;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Lot B — Retrait/restauration d'une annonce de trajet par la modération.
 * Réservé à ADMIN/SUPER_ADMIN (SUPPORT ne reçoit pas CONTENT_REMOVE).
 */
@RestController
@PreAuthorize("hasRole('ADMIN') and hasAuthority('CONTENT_REMOVE')")
public class AdminAnnouncementModerationController {

    private final AnnouncementService announcementService;
    private final UserRepository userRepository;

    public AdminAnnouncementModerationController(AnnouncementService announcementService,
                                                  UserRepository userRepository) {
        this.announcementService = announcementService;
        this.userRepository = userRepository;
    }

    @PostMapping("/admin/announcements/{id}/remove")
    public AdminAnnouncementListItemResponse remove(@PathVariable UUID id,
            @RequestBody @Valid RemoveAnnouncementRequest request, Authentication authentication) {
        AnnouncementEntity announcement = announcementService.removeByAdmin(
                id, adminId(authentication), request.reason());
        return toListItem(announcement);
    }

    @PostMapping("/admin/announcements/{id}/restore")
    public AdminAnnouncementListItemResponse restore(@PathVariable UUID id, Authentication authentication) {
        AnnouncementEntity announcement = announcementService.restoreByAdmin(id, adminId(authentication));
        return toListItem(announcement);
    }

    private UUID adminId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }

    private AdminAnnouncementListItemResponse toListItem(AnnouncementEntity a) {
        String travelerName = a.getTravelerId() != null
                ? userRepository.findById(a.getTravelerId()).map(MatchingTextUtil::buildName).orElse(null)
                : null;
        String corridor = MatchingTextUtil.corridorLabel(a.getDepartureCity(), a.getArrivalCity());
        return new AdminAnnouncementListItemResponse(
                a.getId(), a.getStatus().name(), travelerName,
                corridor, a.getDepartureDate(), a.getAvailableKg(), a.getPricePerKg());
    }
}
