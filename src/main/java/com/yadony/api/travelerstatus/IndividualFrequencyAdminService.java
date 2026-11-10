package com.yadony.api.travelerstatus;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.travelerstatus.dto.AdminIndividualFrequencyFlagRow;
import com.yadony.api.travelerstatus.dto.IndividualFrequencyAlert;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** File admin des particuliers à confirmer (lot 4) : liste, « écarter » et bandeau de la fiche utilisateur. */
@Service
public class IndividualFrequencyAdminService {

    private final IndividualFrequencyFlagRepository flags;
    private final UserRepository users;
    private final AuditService audit;

    public IndividualFrequencyAdminService(IndividualFrequencyFlagRepository flags, UserRepository users,
                                           AuditService audit) {
        this.flags = flags;
        this.users = users;
        this.audit = audit;
    }

    /** Signalements ni résolus ni écartés, le plus ancien d'abord. */
    @Transactional(readOnly = true)
    public List<AdminIndividualFrequencyFlagRow> list() {
        List<IndividualFrequencyFlagEntity> open = flags.findByResolvedAtIsNullAndDismissedAtIsNullOrderByDetectedAtAscIdAsc();
        if (open.isEmpty()) {
            return List.of();
        }
        Map<UUID, UserEntity> byId = users.findAllById(
                        open.stream().map(IndividualFrequencyFlagEntity::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        return open.stream()
                .map(f -> new AdminIndividualFrequencyFlagRow(f.getId(), f.getUserId(),
                        displayName(byId.get(f.getUserId())), f.getTripsCount(), f.getWindowDays(),
                        f.getThreshold(), f.getDetectedAt(), f.isWeeklyApplication()))
                .toList();
    }

    /**
     * Écarte un signalement. Sans effet s'il l'est déjà ou s'il est déjà résolu (la condition a cessé entre-temps :
     * il n'y a plus rien à écarter). Ne touche jamais au compte de l'utilisateur.
     */
    @Transactional
    public void dismiss(UUID flagId, UUID adminId) {
        IndividualFrequencyFlagEntity flag = flags.findById(flagId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "individual-frequency-flag-not-found", "Flag Not Found", "Signalement introuvable"));
        if (flag.getDismissedAt() != null || flag.getResolvedAt() != null) {
            return;
        }
        flag.setDismissedAt(LocalDateTime.now(ZoneOffset.UTC));
        flag.setDismissedBy(adminId);
        flags.save(flag);
        // Jamais de donnée d'entreprise dans l'audit (immuable) : l'utilisateur et les chiffres seulement.
        audit.log("INDIVIDUAL_FREQUENCY_FLAG", flagId, "INDIVIDUAL_FREQUENCY_FLAG_DISMISSED", adminId,
                Map.of("userId", flag.getUserId().toString(), "tripsCount", flag.getTripsCount(),
                        "threshold", flag.getThreshold()));
    }

    /** Bandeau de la fiche utilisateur ; {@code null} sans signalement ouvert (le champ est alors omis du JSON). */
    @Transactional(readOnly = true)
    public IndividualFrequencyAlert openAlertFor(UUID userId) {
        return flags.findFirstByUserIdAndResolvedAtIsNullAndDismissedAtIsNull(userId)
                .map(f -> new IndividualFrequencyAlert(f.getId(), f.getTripsCount(), f.getWindowDays(),
                        f.getThreshold(), f.getDetectedAt(), f.isWeeklyApplication()))
                .orElse(null);
    }

    private static String displayName(UserEntity user) {
        return user == null ? UserEntity.UNKNOWN_DISPLAY_NAME : user.adminDisplayName();
    }
}
