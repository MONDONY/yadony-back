package com.yadony.api.admin;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.RecetteMode;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Désignation en masse des comptes testeurs du mode recette, depuis la liste des utilisateurs
 * de l'admin.
 *
 * <p>Mêmes garde-fous que la désignation unitaire ({@link AdminRecetteTesterController}) :
 * refus 409 {@code recette-disabled} quand le mode est fermé (prod notamment), une entrée
 * {@code audit_log} par changement réel ({@code RECETTE_TESTER_GRANTED / _REVOKED}), rien pour
 * un compte déjà dans l'état demandé. Le lot est appliqué dans une seule transaction.
 */
@Service
public class AdminRecetteTesterService {

    /** Taille maximale d'un lot, alignée sur la sélection « tous les résultats » de l'admin. */
    public static final int MAX_BULK = 200;

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final RecetteMode recetteMode;

    public AdminRecetteTesterService(UserRepository userRepository, AuditService auditService,
                                     RecetteMode recetteMode) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.recetteMode = recetteMode;
    }

    /**
     * @param updated   comptes dont le drapeau a réellement changé (une entrée d'audit chacun)
     * @param unchanged comptes déjà dans l'état demandé
     * @param notFound  identifiants inconnus ou supprimés, dans l'ordre de la demande
     */
    public record BulkResult(int updated, int unchanged, List<UUID> notFound) {}

    /** Le mode recette est ouvert dans cet environnement (staging). */
    public boolean isAvailable() {
        return recetteMode.isEnabled();
    }

    @Transactional
    public BulkResult setBulk(Collection<UUID> userIds, boolean enabled, UUID adminId) {
        if (!recetteMode.isEnabled()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "recette-disabled",
                    "Recette Disabled",
                    "Le mode recette n'est pas activé dans cet environnement");
        }
        Set<UUID> ids = new LinkedHashSet<>(userIds == null ? List.of() : userIds);
        ids.remove(null);
        if (ids.isEmpty() || ids.size() > MAX_BULK) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "recette-bulk-size",
                    "Invalid Selection",
                    "Sélectionnez entre 1 et " + MAX_BULK + " utilisateurs");
        }

        Map<UUID, UserEntity> found = userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));

        List<UUID> notFound = new ArrayList<>();
        List<UserEntity> changed = new ArrayList<>();
        int unchanged = 0;
        for (UUID id : ids) {
            UserEntity user = found.get(id);
            if (user == null) {
                notFound.add(id);
            } else if (user.isRecetteTester() == enabled) {
                unchanged++;
            } else {
                user.setRecetteTester(enabled);
                changed.add(user);
            }
        }
        if (!changed.isEmpty()) {
            userRepository.saveAll(changed);
            String action = enabled
                    ? AdminRecetteTesterController.ACTION_GRANTED
                    : AdminRecetteTesterController.ACTION_REVOKED;
            for (UserEntity user : changed) {
                auditService.log("USER", user.getId(), action, adminId,
                        Map.of("recetteTester", enabled, "bulk", true));
            }
        }
        return new BulkResult(changed.size(), unchanged, notFound);
    }
}
