package com.yadony.api.admin.userview;

import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.admin.dto.AdminAnnouncementListItemResponse;
import com.yadony.api.admin.userview.dto.AdminUserParcelRow;
import com.yadony.api.admin.userview.dto.AdminUserPaymentRow;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Lecture seule de la fiche d'un utilisateur côté admin (synthèse, puis onglets). */
@RestController
@RequestMapping("/admin/users/{userId}")
public class AdminUserViewController {

    private final UserRepository userRepository;
    private final AdminUserOverviewService overviewService;

    private final AdminUserListingService listingService;

    public AdminUserViewController(UserRepository userRepository, AdminUserOverviewService overviewService,
                                   AdminUserListingService listingService) {
        this.userRepository = userRepository;
        this.overviewService = overviewService;
        this.listingService = listingService;
    }

    @GetMapping("/parcels")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('BID_VIEW')")
    public Page<AdminUserParcelRow> parcels(@PathVariable UUID userId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        requireUser(userId);
        return listingService.parcels(userId, page, size);
    }

    @GetMapping("/trips")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('BID_VIEW')")
    public Page<AdminAnnouncementListItemResponse> trips(@PathVariable UUID userId,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        requireUser(userId);
        return listingService.trips(userId, page, size);
    }

    @GetMapping("/payments")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_VIEW')")
    public Page<AdminUserPaymentRow> payments(@PathVariable UUID userId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        requireUser(userId);
        return listingService.payments(userId, page, size);
    }

    @GetMapping("/overview")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_VIEW')")
    public AdminUserOverviewResponse overview(@PathVariable UUID userId, Authentication authentication) {
        UserEntity user = requireUser(userId);
        return overviewService.overview(user, authoritiesOf(authentication));
    }

    /** Utilisateur inconnu ou supprimé (@Where deleted_at IS NULL) : 404 standard. */
    private UserEntity requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));
    }

    private static Set<String> authoritiesOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
