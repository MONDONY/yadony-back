package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.dto.AdminGdprRequestResponse;
import com.yadony.api.admin.dto.GdprExecuteRequest;
import com.yadony.api.auth.AdminGdprService;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Lot C — file des demandes de suppression RGPD et exécution administrateur.
 *
 * <p>{@code /admin/users/gdpr-requests} cohabite avec le {@code /admin/users/{userId}} d'
 * {@code AdminUserController} : le {@code PathPatternParser} classe un segment littéral
 * avant un segment à variable, la route littérale gagne donc. Comportement verrouillé par
 * {@code AdminGdprControllerIT.list_literalPathWinsOverUuidTemplate}.
 */
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminGdprController {

    private final AdminGdprService adminGdprService;
    private final FirebaseContactService firebaseContact;

    public AdminGdprController(AdminGdprService adminGdprService,
                               FirebaseContactService firebaseContact) {
        this.adminGdprService = adminGdprService;
        this.firebaseContact = firebaseContact;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_GDPR_DELETE')")
    @GetMapping("/gdpr-requests")
    public Page<AdminGdprRequestResponse> listRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<UserEntity> users = adminGdprService.listDeletionRequests(PageRequest.of(page, size));
        // Un seul aller-retour Firebase pour toute la page, comme AdminUserController.listUsers.
        Map<String, FirebaseContactService.Contact> contacts = firebaseContact.getContacts(
                users.getContent().stream().map(UserEntity::getFirebaseUid).toList());
        return users.map(u -> AdminGdprRequestResponse.from(
                u, contacts.getOrDefault(u.getFirebaseUid(), FirebaseContactService.Contact.EMPTY)));
    }

    /** Irréversible : 204 sans corps, l'utilisateur ciblé n'existe plus sous sa forme lisible. */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_GDPR_DELETE')")
    @PostMapping("/{userId}/gdpr-execute")
    public ResponseEntity<Void> execute(@PathVariable UUID userId,
                                        @RequestBody @Valid GdprExecuteRequest request,
                                        Authentication authentication) {
        adminGdprService.executeDeletion(userId, adminId(authentication), request.reason());
        return ResponseEntity.noContent().build();
    }

    private UUID adminId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }
}
