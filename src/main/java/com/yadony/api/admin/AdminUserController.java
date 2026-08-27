package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminDeleteUserRequest;
import com.yadony.api.admin.dto.AdminUserDetailResponse;
import com.yadony.api.admin.dto.AdminUserListItemResponse;
import com.yadony.api.admin.dto.DeletionImpactResponse;
import com.yadony.api.admin.dto.MuteMessagingRequest;
import com.yadony.api.admin.dto.ProGrantRequest;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserService;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.billing.ProSubscriptionEntity;
import com.yadony.api.billing.ProSubscriptionRepository;
import com.yadony.api.billing.ProSubscriptionSource;
import com.yadony.api.billing.ProSubscriptionService;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContact;
    private final UserDeletionImpactService deletionImpactService;
    private final AdminUserDeletionService deletionService;
    private final ProSubscriptionService proSubscriptionService;
    private final ProSubscriptionRepository proSubscriptionRepository;

    public AdminUserController(UserService userService,
                               UserRepository userRepository,
                               FirebaseContactService firebaseContact,
                               UserDeletionImpactService deletionImpactService,
                               AdminUserDeletionService deletionService,
                               ProSubscriptionService proSubscriptionService,
                               ProSubscriptionRepository proSubscriptionRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.firebaseContact = firebaseContact;
        this.deletionImpactService = deletionImpactService;
        this.deletionService = deletionService;
        this.proSubscriptionService = proSubscriptionService;
        this.proSubscriptionRepository = proSubscriptionRepository;
    }

    @PreAuthorize("hasAuthority('USER_VIEW')")
    @GetMapping
    public Page<AdminUserListItemResponse> listUsers(
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) KycStatus kyc,
            @RequestParam(required = false) Boolean pro,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String normalizedQuery = (query != null && !query.isBlank()) ? query.trim() : null;
        String queryLike = normalizedQuery != null ? "%" + normalizedQuery + "%" : null;
        String normalizedCity = (city != null && !city.isBlank()) ? city.trim() : null;

        Page<UserEntity> users = userRepository.findAdminFiltered(
                status != null ? status.name() : null,
                kyc != null ? kyc.name() : null,
                pro,
                normalizedCity,
                queryLike,
                resolveQueryToFirebaseUid(normalizedQuery),
                role != null ? role.name() : null,
                PageRequest.of(page, size)
        );

        Map<String, FirebaseContactService.Contact> contacts = firebaseContact.getContacts(
                users.getContent().stream().map(UserEntity::getFirebaseUid).toList());
        return users.map(u -> AdminUserListItemResponse.from(
                u, contacts.getOrDefault(u.getFirebaseUid(), FirebaseContactService.Contact.EMPTY)));
    }

    /**
     * Téléphone et email ne sont plus en base : une recherche qui en vise un est résolue
     * par Firebase en UID, puis appariée exactement. Les noms restent en recherche
     * partielle SQL.
     *
     * <p>La forme du terme choisit le lookup, sinon toute recherche par nom, le cas
     * dominant, paierait deux appels réseau voués à échouer avant même que le SQL parte.
     */
    private String resolveQueryToFirebaseUid(String normalizedQuery) {
        if (normalizedQuery == null) {
            return null;
        }
        if (normalizedQuery.contains("@")) {
            return firebaseContact.findUidByEmail(normalizedQuery).orElse(null);
        }
        // Les numéros sont stockés en E.164 côté Firebase, donc préfixés de « + ».
        if (normalizedQuery.startsWith("+")) {
            return firebaseContact.findUidByPhone(normalizedQuery).orElse(null);
        }
        return null;
    }

    @PreAuthorize("hasAuthority('USER_VIEW')")
    @GetMapping("/{userId}")
    public AdminUserDetailResponse getUser(@PathVariable UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));
        return detail(user);
    }

    @PreAuthorize("hasAuthority('USER_SUSPEND')")
    @PostMapping("/{userId}/suspend")
    public AdminUserDetailResponse suspendUser(@PathVariable UUID userId,
            @RequestBody SuspendBanRequest request, Authentication authentication) {
        return detail(userService.suspendUser(userId, request.reason(), adminId(authentication)));
    }

    @PreAuthorize("hasAuthority('USER_BAN')")
    @PostMapping("/{userId}/ban")
    public AdminUserDetailResponse banUser(@PathVariable UUID userId,
            @RequestBody SuspendBanRequest request, Authentication authentication) {
        return detail(userService.banUser(userId, request.reason(), adminId(authentication)));
    }

    @PreAuthorize("hasAuthority('USER_SUSPEND')")
    @PostMapping("/{userId}/unsuspend")
    public AdminUserDetailResponse unsuspendUser(@PathVariable UUID userId) {
        return detail(userService.unsuspendUser(userId));
    }

    @PreAuthorize("hasAuthority('USER_SUSPEND')")
    @PostMapping("/{userId}/suspend-publishing")
    public ResponseEntity<Void> suspendPublishing(@PathVariable UUID userId,
            @RequestParam(required = false) String reason) {
        userService.suspendPublishing(userId, reason);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasAuthority('USER_SUSPEND')")
    @PostMapping("/{userId}/lift-publishing-suspension")
    public ResponseEntity<Void> liftPublishingSuspension(@PathVariable UUID userId) {
        userService.liftPublishingSuspension(userId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasAuthority('USER_COMMISSION')")
    @PutMapping("/{userId}/commission-rate")
    public AdminUserDetailResponse setCommissionRate(
            @PathVariable UUID userId,
            @RequestBody @jakarta.validation.Valid CommissionRateOverrideRequest request) {
        return detail(userService.setCommissionRateOverride(userId, request.rate()));
    }

    // Lot B — Coupure de messagerie. SUPPORT REÇOIT USER_MESSAGE_MUTE (arbitrage produit du
    // Lot C : il peut déjà bannir, geste bien plus sévère). Voir AdminRole.SUPPORT.
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_MESSAGE_MUTE')")
    @PostMapping("/{userId}/mute-messaging")
    public AdminUserDetailResponse muteMessaging(@PathVariable UUID userId,
            @RequestBody @jakarta.validation.Valid MuteMessagingRequest request,
            Authentication authentication) {
        return detail(userService.muteMessaging(userId, request.durationHours(), request.reason(),
                adminId(authentication)));
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_MESSAGE_MUTE')")
    @PostMapping("/{userId}/unmute-messaging")
    public AdminUserDetailResponse unmuteMessaging(@PathVariable UUID userId,
            Authentication authentication) {
        return detail(userService.unmuteMessaging(userId, adminId(authentication)));
    }

    /** Consultation seule : aucun effet de bord, l'administrateur peut l'ouvrir à volonté. */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_DELETE')")
    @GetMapping("/{userId}/deletion-impact")
    public DeletionImpactResponse deletionImpact(@PathVariable UUID userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));
        return deletionImpactService.report(userId);
    }

    /**
     * {@code POST} et non {@code DELETE} : un corps de requête est nécessaire pour le motif, et
     * tous les endpoints admin existants suivent déjà cette forme.
     */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_DELETE')")
    @PostMapping("/{userId}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable UUID userId,
                           @Valid @RequestBody AdminDeleteUserRequest request,
                           Authentication authentication) {
        deletionService.delete(userId, adminId(authentication), request.reasonCode(), request.reason());
    }

    /**
     * Offre un accès PRO gratuit : partenariat, geste commercial.
     *
     * <p>{@code POST} et non {@code PUT} : un corps est nécessaire pour le motif, et
     * c'est la forme retenue par les endpoints admin du dépôt.
     */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_PRO_GRANT')")
    @PostMapping("/{userId}/pro-grant")
    public AdminUserDetailResponse grantPro(@PathVariable UUID userId,
                                            @Valid @RequestBody ProGrantRequest request,
                                            Authentication authentication) {
        proSubscriptionService.grantByAdmin(userId, adminId(authentication), request.reason());
        return detail(userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "Not Found", "Utilisateur introuvable")));
    }

    /**
     * Révoque un accès offert.
     *
     * <p>Refusé si l'abonnement n'est pas un octroi administrateur : fermer une ligne
     * Stripe ici désynchroniserait la base et Stripe, l'utilisateur perdant son accès
     * tout en restant débité. La résiliation d'un abonnement payant passe par le
     * Customer Portal.
     */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_PRO_GRANT')")
    @DeleteMapping("/{userId}/pro-grant")
    public AdminUserDetailResponse revokePro(@PathVariable UUID userId,
                                             Authentication authentication) {
        ProSubscriptionEntity sub = proSubscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "no-subscription", "Not Found",
                        "Aucun abonnement PRO sur ce compte"));

        if (sub.getSource() != ProSubscriptionSource.ADMIN_GRANT) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "not-an-admin-grant", "Not An Admin Grant",
                    "Cet abonnement n'est pas un accès offert : il se résilie depuis Stripe.");
        }

        proSubscriptionService.cancel(sub);
        return detail(userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "Not Found", "Utilisateur introuvable")));
    }

    private AdminUserDetailResponse detail(UserEntity user) {
        return AdminUserDetailResponse.from(user, firebaseContact.getContact(user.getFirebaseUid()));
    }

    /**
     * Identifiant de l'administrateur qui agit, pour la trace d'audit.
     *
     * <p>{@code audit_log} est immuable : une trace qui designe la CIBLE comme acteur — ce
     * qui etait le cas ici — ne pourra jamais etre corrigee, et l'administrateur responsable
     * resterait introuvable.
     */
    private UUID adminId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }

    record SuspendBanRequest(String reason) {}
}
