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
import java.util.regex.Pattern;

/** Déclaration, par le voyageur, de son statut légal (particulier ou professionnel). */
@Service
public class TravelerStatusService {

    /** Version des conditions que le voyageur accepte en déclarant son statut. */
    public static final String TERMS_VERSION = "2026-11-10";

    private static final Pattern REGISTRATION_NUMBER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ./-]*$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

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
        Declaration d = validate(r);
        // Verrou par utilisateur, puis relecture : l'entité chargée avant le verrou peut être périmée.
        UserEntity user = userRepository.findByIdForUpdate(loaded.getId()).orElse(loaded);

        user.setTravelerStatus(d.status());
        user.setTravelerStatusDeclaredAt(Instant.now());
        user.setTravelerStatusTermsVersion(TERMS_VERSION);
        // Un particulier n'a aucune donnée d'entreprise : contrainte chk_users_traveler_status_pro_fields.
        user.setBusinessName(d.businessName());
        user.setBusinessRegistrationNumber(d.registrationNumber());
        user.setBusinessCountry(d.country());
        userRepository.save(user);

        // Jamais le numéro ni la raison sociale dans l'audit (immuable) : le statut et le pays seulement.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", d.status().name());
        if (d.country() != null) {
            payload.put("country", d.country());
        }
        auditService.log("USER", user.getId(), "TRAVELER_STATUS_DECLARED", user.getId(), payload);
        events.publishEvent(new TravelerStatusChangedEvent(user.getId(), d.status()));

        return new TravelerStatusResponse(user.getTravelerStatus(), user.getTravelerStatusDeclaredAt(),
                user.getBusinessName(), user.getBusinessRegistrationNumber(), user.getBusinessCountry());
    }

    private record Declaration(TravelerStatus status, String businessName, String registrationNumber,
                               String country) {
    }

    private static Declaration validate(TravelerStatusRequest r) {
        if (r == null || r.status() == null) {
            throw invalid("Le statut est obligatoire");
        }
        if (r.status() == TravelerStatus.INDIVIDUAL) {
            if (!Boolean.TRUE.equals(r.personalUseConfirmed())) {
                throw invalid("Confirmez que vous voyagez à titre personnel");
            }
            return new Declaration(TravelerStatus.INDIVIDUAL, null, null, null);
        }
        String name = clean(r.businessName());
        String number = clean(r.registrationNumber());
        String country = r.country();
        if (name == null || name.length() < 2 || name.length() > 120 || hasControlChars(name)) {
            throw invalid("Raison sociale invalide");
        }
        if (number == null || number.length() < 4 || number.length() > 40
                || !REGISTRATION_NUMBER.matcher(number).matches()) {
            throw invalid("Numéro d'immatriculation invalide");
        }
        if (country == null || !COUNTRY.matcher(country).matches()) {
            throw invalid("Pays d'immatriculation invalide");
        }
        return new Declaration(TravelerStatus.PROFESSIONAL, name, number, country);
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static boolean hasControlChars(String s) {
        return s.codePoints().anyMatch(Character::isISOControl);
    }

    private static YadonyBusinessException invalid(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "traveler-status-invalid", "Invalid Traveler Status", detail);
    }
}
