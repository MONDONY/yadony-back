package com.yadony.api.activation;

import com.yadony.api.activation.dto.ActivationResponse;
import com.yadony.api.activation.dto.ActivationResponse.Opportunities;
import com.yadony.api.activation.dto.ActivationResponse.PackageItem;
import com.yadony.api.activation.dto.ActivationResponse.TripItem;
import com.yadony.api.activation.dto.DeclareIntentRequest;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.CountryCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Intention déclarée, première action et opportunités de la ligne (guidage après KYC). */
@Service
public class ActivationService {

    static final int OPPORTUNITY_DAYS = 15;
    static final int OPPORTUNITY_LIMIT = 3;

    private final UserRepository userRepository;
    private final ActivationRepository activationRepository;
    private final AuditService auditService;

    public ActivationService(UserRepository userRepository, ActivationRepository activationRepository,
                             AuditService auditService) {
        this.userRepository = userRepository;
        this.activationRepository = activationRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public ActivationResponse getActivation(String firebaseUid) {
        return toResponse(requireUser(firebaseUid));
    }

    @Transactional
    public ActivationResponse declareIntent(String firebaseUid, DeclareIntentRequest request) {
        if (request.source() == IntentSource.INFERRED) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-intent-source",
                    "Invalid Intent Source", "La source INFERRED est réservée au serveur");
        }
        String destination = request.destinationCountry() == null
                ? null : request.destinationCountry().toUpperCase(Locale.ROOT);
        if (destination != null && !CountryCatalog.isSupported(destination)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "unsupported-destination-country",
                    "Unsupported Destination Country", "Ce pays de destination n'est pas pris en charge");
        }
        UserEntity user = requireUser(firebaseUid);
        user.setIntent(request.intent().name());
        user.setIntentDestinationCountry(destination);
        user.setIntentSource(request.source().name());
        user.setIntentDeclaredAt(Instant.now());
        userRepository.save(user);

        Map<String, Object> payload = new HashMap<>();
        payload.put("intent", request.intent().name());
        payload.put("source", request.source().name());
        payload.put("destinationCountry", destination);
        auditService.log("USER", user.getId(), "INTENT_DECLARED", user.getId(), payload);
        return toResponse(user);
    }

    /** Trajets (expéditeur, les deux) ou colis (voyageur) vers le pays visé, sur 15 jours. */
    @Transactional(readOnly = true)
    public Opportunities opportunitiesFor(UserEntity user) {
        String intent = user.getIntent();
        String destination = user.getIntentDestinationCountry();
        if (intent == null || destination == null) {
            return Opportunities.none();
        }
        LocalDate from = LocalDate.now(ZoneOffset.UTC);
        LocalDate to = from.plusDays(OPPORTUNITY_DAYS);
        if (UserIntent.TRAVELER.name().equals(intent)) {
            long total = activationRepository.countPackagesTowards(destination, from, to, user.getId());
            List<PackageItem> items = activationRepository
                    .findPackagesTowards(destination, from, to, user.getId(), OPPORTUNITY_LIMIT).stream()
                    .map(r -> new PackageItem(UUID.fromString(r.getId()), r.getDepartureCity(), r.getArrivalCity(),
                            r.getDesiredDate(), toDouble(r.getWeightKg())))
                    .toList();
            return new Opportunities("PACKAGES", total, List.of(), items);
        }
        long total = activationRepository.countTripsTowards(destination, from, to, user.getId());
        List<TripItem> items = activationRepository
                .findTripsTowards(destination, from, to, user.getId(), OPPORTUNITY_LIMIT).stream()
                .map(r -> new TripItem(UUID.fromString(r.getId()), r.getDepartureCity(), r.getArrivalCity(),
                        r.getDepartureDate(), toDouble(r.getAvailableKg()), toDouble(r.getPricePerKg()),
                        r.getCurrency()))
                .toList();
        return new Opportunities("TRIPS", total, items, List.of());
    }

    private ActivationResponse toResponse(UserEntity user) {
        return new ActivationResponse(
                user.getIntent(),
                user.getIntentDestinationCountry(),
                user.getKycStatus() == KycStatus.VERIFIED,
                activationRepository.hasFirstAction(user.getId()) == 1,
                opportunitiesFor(user));
    }

    private UserEntity requireUser(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found",
                        "User Not Found", "Utilisateur introuvable"));
    }

    private static Double toDouble(Number n) {
        return n == null ? null : n.doubleValue();
    }
}
