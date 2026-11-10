package com.yadony.api.travelerstatus;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.travelerstatus.dto.TravelerStatusRequest;
import com.yadony.api.travelerstatus.dto.TravelerStatusResponse;
import com.yadony.api.travelerstatus.events.TravelerStatusChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Déclaration, par le voyageur, de son statut de particulier. Le statut professionnel n'est plus déclaratif :
 * il vient de l'approbation d'une demande GP Pro ({@code GpProAdminService.approve}).
 */
@Service
public class TravelerStatusService {

    /** Version des conditions que le voyageur accepte en déclarant son statut. */
    public static final String TERMS_VERSION = "2026-11-10";

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;

    public TravelerStatusService(UserRepository userRepository, AuditService auditService,
                                 ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.events = events;
    }

    @Transactional
    public TravelerStatusResponse declare(String firebaseUid, TravelerStatusRequest r) {
        UserEntity loaded = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));
        // Les contrôles passent avant tout verrou et toute écriture : une déclaration refusée ne laisse rien.
        validate(r);
        // Verrou par utilisateur, puis relecture : l'entité chargée avant le verrou peut être périmée.
        UserEntity user = userRepository.findByIdForUpdate(loaded.getId()).orElse(loaded);
        if (user.isGpPro() && user.getTravelerStatus() == TravelerStatus.PROFESSIONAL) {
            // Redevenir particulier se fait par la sortie GP Pro (POST /gp-pro/resign), qui retire le badge
            // et le statut dans la même transaction : jamais un GP Pro avec un statut particulier.
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "gp-pro-active", "GP Pro Active",
                    "Pour redevenir particulier, quittez le statut GP Pro.");
        }

        user.setTravelerStatus(TravelerStatus.INDIVIDUAL);
        user.setTravelerStatusDeclaredAt(Instant.now());
        user.setTravelerStatusTermsVersion(TERMS_VERSION);
        // Un particulier n'a aucune donnée d'entreprise : contrainte chk_users_traveler_status_pro_fields.
        user.setBusinessName(null);
        user.setBusinessRegistrationNumber(null);
        user.setBusinessCountry(null);
        userRepository.save(user);

        // Le statut seul dans l'audit (immuable).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", TravelerStatus.INDIVIDUAL.name());
        auditService.log("USER", user.getId(), "TRAVELER_STATUS_DECLARED", user.getId(), payload);
        events.publishEvent(new TravelerStatusChangedEvent(user.getId(), TravelerStatus.INDIVIDUAL));

        return new TravelerStatusResponse(user.getTravelerStatus(), user.getTravelerStatusDeclaredAt(),
                user.getBusinessName(), user.getBusinessRegistrationNumber(), user.getBusinessCountry());
    }

    private static void validate(TravelerStatusRequest r) {
        if (r == null || r.status() == null) {
            throw invalid("Le statut est obligatoire");
        }
        if (r.status() == TravelerStatus.PROFESSIONAL) {
            throw invalid("Utilisez la demande GP Pro");
        }
        if (!Boolean.TRUE.equals(r.personalUseConfirmed())) {
            throw invalid("Confirmez que vous voyagez à titre personnel");
        }
    }

    private static YadonyBusinessException invalid(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "traveler-status-invalid", "Invalid Traveler Status", detail);
    }
}
