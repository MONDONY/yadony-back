package com.yadony.api.kyc;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.kyc.dto.KycAdminStatusResponse;
import com.yadony.api.kyc.dto.KycHistoryEntry;
import com.yadony.api.kyc.dto.KycQueueItemResponse;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * File de revue des verifications d'identite et decisions d'administration (valider,
 * refuser, revoquer).
 *
 * <p>Toutes les transitions passent par {@link KycStatusTransitionService} : une validation
 * manuelle publie le meme {@code UserKycVerifiedEvent} qu'un webhook, un refus manuel la meme
 * notification qu'un refus du fournisseur.
 *
 * <p>La fiche ({@link #detail}) relit la session chez le fournisseur de la LIGNE, par
 * {@link KycAdminService#getForUser} : la regle asymetrique d'{@code IdentityProviderResolver}
 * reste la seule a choisir le fournisseur.
 */
@Service
public class KycAdminReviewService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    private static final int HISTORY_SIZE = 20;
    private static final String SESSION_PLACEHOLDER = "{sessionId}";
    private static final String MASK = "••••";

    /** Actions ecrites par les webhooks des fournisseurs ({@link KycStatusTransitionService}). */
    private static final Set<String> PROVIDER_ACTIONS = Set.of(
            "KYC_VERIFIED", "KYC_REJECTED", "KYC_IN_REVIEW", "KYC_ABANDONED", "KYC_EXPIRED", "KYC_CANCELED");
    /** Actions ecrites par l'utilisateur lui-meme ({@link KycService}). */
    private static final Set<String> USER_ACTIONS = Set.of("KYC_SESSION_CREATED", "KYC_SESSION_ABANDONED");

    /** Etat metier d'une ligne dans la file. */
    enum QueueStatus { IN_REVIEW, IN_PROGRESS, NOT_STARTED, REJECTED, VERIFIED }

    private final KycRepository kycRepository;
    private final UserRepository userRepository;
    private final KycAdminService kycAdminService;
    private final KycStatusTransitionService transitions;
    private final AuditLogRepository auditLogRepository;
    private final AdminEmailDirectory adminDirectory;
    private final FirebaseContactService firebaseContact;
    private final EntityManager em;
    private final Map<VerificationProviderKind, String> consoleUrlTemplates;

    public KycAdminReviewService(KycRepository kycRepository,
                                 UserRepository userRepository,
                                 KycAdminService kycAdminService,
                                 KycStatusTransitionService transitions,
                                 AuditLogRepository auditLogRepository,
                                 AdminEmailDirectory adminDirectory,
                                 FirebaseContactService firebaseContact,
                                 EntityManager em,
                                 @Value("${yadony.kyc.console-url.stripe:}") String stripeConsoleUrl,
                                 @Value("${yadony.kyc.console-url.didit:}") String diditConsoleUrl) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.kycAdminService = kycAdminService;
        this.transitions = transitions;
        this.auditLogRepository = auditLogRepository;
        this.adminDirectory = adminDirectory;
        this.firebaseContact = firebaseContact;
        this.em = em;
        this.consoleUrlTemplates = Map.of(
                VerificationProviderKind.STRIPE, Objects.requireNonNullElse(stripeConsoleUrl, ""),
                VerificationProviderKind.DIDIT, Objects.requireNonNullElse(diditConsoleUrl, ""));
    }

    // ── File ──────────────────────────────────────────────────────────────────

    /**
     * File filtree et paginee. {@code IN_REVIEW} (defaut) : du plus ancien au plus recent, la
     * demande qui attend depuis le plus longtemps d'abord ; autres etats : du plus recent au
     * plus ancien. {@code from}/{@code to} sont inclus et portent sur la date de passage en
     * revue pour {@code IN_REVIEW}, sur la derniere mise a jour de la ligne sinon.
     */
    @Transactional(readOnly = true)
    public Page<KycQueueItemResponse> queue(String status, VerificationProviderKind provider, String query,
                                            LocalDate from, LocalDate to, int page, int size) {
        QueueStatus bucket = parseStatus(status);
        PageRequest pageable = PageRequest.of(Math.max(page, 0),
                size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE));

        StringBuilder where = new StringBuilder(
                " FROM KycVerificationEntity k, UserEntity u WHERE u.id = k.userId");
        Map<String, Object> params = new HashMap<>();
        switch (bucket) {
            case IN_REVIEW -> where.append(" AND k.status = :pending AND k.submittedAt IS NOT NULL");
            case IN_PROGRESS -> where.append(
                    " AND k.status = :pending AND k.submittedAt IS NULL AND u.kycStatus <> :notStarted");
            case NOT_STARTED -> where.append(
                    " AND k.status = :pending AND k.submittedAt IS NULL AND u.kycStatus = :notStarted");
            case REJECTED, VERIFIED -> {
                where.append(" AND k.status = :rowStatus");
                params.put("rowStatus", KycVerificationStatus.valueOf(bucket.name()));
            }
        }
        if (bucket == QueueStatus.IN_REVIEW || bucket == QueueStatus.IN_PROGRESS
                || bucket == QueueStatus.NOT_STARTED) {
            params.put("pending", KycVerificationStatus.PENDING);
        }
        if (bucket == QueueStatus.IN_PROGRESS || bucket == QueueStatus.NOT_STARTED) {
            params.put("notStarted", KycStatus.NOT_STARTED);
        }
        if (provider != null) {
            where.append(" AND k.provider = :provider");
            params.put("provider", provider);
        }
        if (!appendQuery(query, where, params)) {
            return Page.empty(pageable);
        }
        String dateField = bucket == QueueStatus.IN_REVIEW ? "k.submittedAt" : "k.updatedAt";
        if (from != null) {
            where.append(" AND ").append(dateField).append(" >= :from");
            params.put("from", from.atStartOfDay());
        }
        if (to != null) {
            where.append(" AND ").append(dateField).append(" < :toExclusive");
            params.put("toExclusive", to.plusDays(1).atStartOfDay());
        }
        String order = bucket == QueueStatus.IN_REVIEW
                ? " ORDER BY k.submittedAt ASC, k.id ASC"
                : " ORDER BY k.updatedAt DESC, k.id ASC";

        Query count = em.createQuery("SELECT COUNT(k)" + where);
        Query select = em.createQuery("SELECT k, u" + where + order)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize());
        params.forEach((name, value) -> {
            count.setParameter(name, value);
            select.setParameter(name, value);
        });
        long total = (Long) count.getSingleResult();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = select.getResultList();
        return new PageImpl<>(toItems(rows), pageable, total);
    }

    private static QueueStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return QueueStatus.IN_REVIEW;
        }
        try {
            return QueueStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "kyc-queue-status-invalid",
                    "Bad Request", "Statut de file inconnu : " + status);
        }
    }

    /**
     * Terme libre : UUID utilisateur exact, telephone E.164 (resolu par Firebase, seule source
     * du numero), sinon nom ou prenom partiels. Rend {@code false} quand le terme ne peut
     * correspondre a personne (telephone inconnu) : la file est alors vide sans requete.
     */
    private boolean appendQuery(String query, StringBuilder where, Map<String, Object> params) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String term = query.trim();
        Optional<UUID> userId = parseUuid(term);
        if (userId.isPresent()) {
            where.append(" AND u.id = :userId");
            params.put("userId", userId.get());
            return true;
        }
        if (term.startsWith("+")) {
            Optional<String> uid = firebaseContact.findUidByPhone(term.replace(" ", ""));
            if (uid.isEmpty()) {
                return false;
            }
            where.append(" AND u.firebaseUid = :firebaseUid");
            params.put("firebaseUid", uid.get());
            return true;
        }
        where.append(" AND (LOWER(u.firstName) LIKE :name ESCAPE '\\' OR LOWER(u.lastName) LIKE :name ESCAPE '\\'"
                + " OR LOWER(CONCAT(COALESCE(u.firstName, ''), ' ', COALESCE(u.lastName, ''))) LIKE :name ESCAPE '\\')");
        params.put("name", "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%");
        return true;
    }

    private static Optional<UUID> parseUuid(String term) {
        try {
            return Optional.of(UUID.fromString(term));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private List<KycQueueItemResponse> toItems(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> uids = rows.stream().map(r -> ((UserEntity) r[1]).getFirebaseUid()).toList();
        Map<String, FirebaseContactService.Contact> contacts = firebaseContact.getContacts(uids);
        Set<UUID> deciders = new HashSet<>();
        rows.forEach(r -> {
            UUID admin = ((KycVerificationEntity) r[0]).getDecidedByAdminId();
            if (admin != null) deciders.add(admin);
        });
        Map<UUID, String> emails = deciders.isEmpty() ? Map.of() : adminDirectory.emailsOf(deciders);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        List<KycQueueItemResponse> items = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            KycVerificationEntity kyc = (KycVerificationEntity) row[0];
            UserEntity user = (UserEntity) row[1];
            QueueStatus status = queueStatusOf(kyc, user);
            FirebaseContactService.Contact contact = contacts == null ? null : contacts.get(user.getFirebaseUid());
            LocalDateTime submittedAt = kyc.getSubmittedAt() != null ? kyc.getSubmittedAt() : kyc.getUpdatedAt();
            Long waitingHours = status == QueueStatus.IN_REVIEW && submittedAt != null
                    ? Duration.between(submittedAt, now).toHours()
                    : null;
            items.add(new KycQueueItemResponse(
                    user.getId(),
                    displayName(user),
                    maskPhone(contact != null ? contact.phoneNumber() : null),
                    kyc.getProvider() != null ? kyc.getProvider().name() : null,
                    user.getKycStatus().name(),
                    kyc.getStatus().name(),
                    status.name(),
                    kyc.getRejectionCode(),
                    kyc.getRejectionReason(),
                    kyc.getDecisionKind() != null ? kyc.getDecisionKind().name() : null,
                    kyc.getDecidedAt(),
                    kyc.getDecidedByAdminId() != null ? emails.get(kyc.getDecidedByAdminId()) : null,
                    submittedAt,
                    waitingHours));
        }
        return items;
    }

    private static QueueStatus queueStatusOf(KycVerificationEntity kyc, UserEntity user) {
        return switch (kyc.getStatus()) {
            case VERIFIED -> QueueStatus.VERIFIED;
            case REJECTED -> QueueStatus.REJECTED;
            case PENDING -> kyc.getSubmittedAt() != null ? QueueStatus.IN_REVIEW
                    : user.getKycStatus() == KycStatus.NOT_STARTED ? QueueStatus.NOT_STARTED
                    : QueueStatus.IN_PROGRESS;
        };
    }

    private static String displayName(UserEntity user) {
        String name = (Objects.toString(user.getFirstName(), "") + " "
                + Objects.toString(user.getLastName(), "")).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }

    /** Seuls les quatre derniers chiffres restent lisibles : la file n'a pas a exposer le numero. */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        return digits.length() <= 4 ? MASK : MASK + " " + digits.substring(digits.length() - 4);
    }

    // ── Fiche ─────────────────────────────────────────────────────────────────

    /**
     * Fiche KYC d'un utilisateur : la vue live de {@link KycAdminService#getForUser}, enrichie
     * de la derniere decision, du lien console et des 20 dernieres entrees d'audit. Hors
     * transaction : la relecture chez le fournisseur est un appel reseau.
     */
    public KycAdminStatusResponse detail(UUID userId) {
        KycAdminStatusResponse base = kycAdminService.getForUser(userId);
        Optional<KycVerificationEntity> row = kycRepository.findByUserId(userId);
        if (row.isEmpty()) {
            return base.withReview(null, null, null, null, null, List.of());
        }
        KycVerificationEntity kyc = row.get();

        List<AuditLogEntity> entries = auditLogRepository.findKycHistory(
                List.of(kyc.getId(), userId), PageRequest.of(0, HISTORY_SIZE));
        Set<UUID> admins = new HashSet<>();
        if (kyc.getDecidedByAdminId() != null) admins.add(kyc.getDecidedByAdminId());
        entries.stream().filter(e -> "ADMIN".equals(actorKind(e.getAction())) && e.getActorId() != null)
                .forEach(e -> admins.add(e.getActorId()));
        Map<UUID, String> emails = admins.isEmpty() ? Map.of() : adminDirectory.emailsOf(admins);

        List<KycHistoryEntry> history = entries.stream().map(e -> {
            String kind = actorKind(e.getAction());
            return new KycHistoryEntry(e.getAction(), e.getCreatedAt(), kind,
                    "ADMIN".equals(kind) && e.getActorId() != null ? emails.get(e.getActorId()) : null,
                    historyDetail(e.getPayload()));
        }).toList();

        String template = kyc.getProvider() != null ? consoleUrlTemplates.get(kyc.getProvider()) : null;
        return base.withReview(
                kyc.getDecisionKind() != null ? kyc.getDecisionKind().name() : null,
                kyc.getDecidedAt(),
                kyc.getDecidedByAdminId() != null ? emails.get(kyc.getDecidedByAdminId()) : null,
                kyc.getDecisionReason(),
                consoleUrl(template, kyc.getVerificationSessionId()),
                history);
    }

    static String actorKind(String action) {
        if (action == null) return "SYSTEM";
        if (action.endsWith("_BY_ADMIN")) return "ADMIN";
        if (PROVIDER_ACTIONS.contains(action)) return "PROVIDER";
        if (USER_ACTIONS.contains(action)) return "USER";
        return "SYSTEM";
    }

    private static String historyDetail(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        String code = text(payload.get("code"));
        String reason = text(payload.get("reason"));
        if (code != null && reason != null) return code + " : " + reason;
        if (code != null) return code;
        if (reason != null) return reason;
        return text(payload.get("provider"));
    }

    private static String text(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    /** Lien console construit depuis un modele {@code ...{sessionId}...} ; sans modele, aucun lien. */
    static String consoleUrl(String template, String sessionId) {
        if (template == null || template.isBlank() || sessionId == null) {
            return null;
        }
        return template.replace(SESSION_PLACEHOLDER, URLEncoder.encode(sessionId, StandardCharsets.UTF_8));
    }

    // ── Décisions ─────────────────────────────────────────────────────────────

    /**
     * Valide une identite dont les pieces existent chez le fournisseur. Le cache de recherche
     * est vide : il porte le badge « identite verifiee » des trajets pendant 5 minutes.
     */
    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public void approve(UUID userId, UUID adminId, String reason) {
        UserEntity user = requireUser(userId);
        KycVerificationEntity kyc = requireProviderSession(userId);
        if (kyc.getStatus() == KycVerificationStatus.VERIFIED) {
            throw alreadyVerified();
        }
        transitions.approveByAdmin(kyc, user, adminId, reason);
    }

    /** Refuse la session courante ; une ligne deja verifiee se revoque, elle ne se refuse pas. */
    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public void reject(UUID userId, UUID adminId, String code, String reason) {
        requireCatalogCode(code);
        UserEntity user = requireUser(userId);
        KycVerificationEntity kyc = requireProviderSession(userId);
        if (kyc.getStatus() == KycVerificationStatus.VERIFIED) {
            throw alreadyVerified();
        }
        transitions.rejectByAdmin(kyc, user, adminId, code, reason);
    }

    /**
     * Retire une verification acquise. Bloque les NOUVELLES actions gardees par le KYC ; les
     * envois, paiements et versements deja engages ne sont pas touches (voir la PR).
     */
    @Transactional
    @CacheEvict(value = "announcements-search", allEntries = true)
    public void revoke(UUID userId, UUID adminId, String code, String reason) {
        requireCatalogCode(code);
        UserEntity user = requireUser(userId);
        Optional<KycVerificationEntity> row = kycRepository.findByUserId(userId);
        if (row.isEmpty()) {
            // Compte verifie sans ligne (anterieur a kyc_verifications) : aucune ligne ou tracer
            // la decision, la revocation serait invisible dans la file.
            if (user.getKycStatus() == KycStatus.VERIFIED) {
                throw noProviderSession();
            }
            throw notVerified();
        }
        KycVerificationEntity kyc = row.get();
        if (kyc.getStatus() != KycVerificationStatus.VERIFIED) {
            throw notVerified();
        }
        transitions.revokeByAdmin(kyc, user, adminId, code, reason);
    }

    private UserEntity requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "Not Found", "Utilisateur introuvable"));
    }

    /** On ne decide que sur une identite dont les pieces existent chez le fournisseur. */
    private KycVerificationEntity requireProviderSession(UUID userId) {
        return kycRepository.findByUserId(userId)
                .filter(kyc -> kyc.getVerificationSessionId() != null)
                .orElseThrow(KycAdminReviewService::noProviderSession);
    }

    private static void requireCatalogCode(String code) {
        if (!KycRejectionCodes.isValid(code)) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "kyc-reject-code-invalid",
                    "Bad Request", "Code de refus inconnu",
                    new LinkedHashMap<>(Map.of("allowedCodes", KycRejectionCodes.ALL)));
        }
    }

    private static YadonyBusinessException noProviderSession() {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "kyc-no-provider-session",
                "Unprocessable", "Aucune session de vérification chez le fournisseur pour cet utilisateur");
    }

    private static YadonyBusinessException alreadyVerified() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "kyc-already-verified",
                "Conflict", "L'identité de cet utilisateur est déjà vérifiée");
    }

    private static YadonyBusinessException notVerified() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "kyc-not-verified",
                "Conflict", "L'identité de cet utilisateur n'est pas vérifiée");
    }
}
