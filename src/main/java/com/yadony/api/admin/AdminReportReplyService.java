package com.yadony.api.admin;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportPhotoEntity;
import com.yadony.api.signalements.ReportPhotoRepository;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportTargetType;
import com.yadony.api.support.SupportCategory;
import com.yadony.api.support.SupportTicketEntity;
import com.yadony.api.support.SupportTicketService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Répondre au signalant d'un bug de l'app (cible {@code APP}, scarabée) dans une
 * conversation support. Orchestration admin : lit le signalement (package
 * {@code signalements}) et délègue le fil au {@link SupportTicketService} (package
 * {@code support}), comme les autres contrôleurs admin.
 *
 * <p>Règles :
 * <ul>
 *   <li>Le signalement garde son statut : répondre n'est pas traiter.</li>
 *   <li>Ticket lié encore ouvert : la réponse s'y ajoute (l'appelant se l'assigne ou le
 *       reprend s'il le faut). Ticket lié résolu (terminal, jamais rouvert) ou disparu :
 *       une nouvelle conversation s'ouvre et le lien la suit.</li>
 *   <li>Nouvelle conversation : sujet « Votre signalement du {date} » et message de
 *       contexte (description, écran, date, captures copiées sous le préfixe support du
 *       signalant) dans la langue préférée du signalant, puis la réponse de l'admin.</li>
 * </ul>
 */
@Service
public class AdminReportReplyService {

    /** Place laissée au reste du message de contexte (en-tête, écran, date). */
    static final int MAX_CONTEXT_DESCRIPTION = 3000;

    /** Sections de l'app dont la route se lit en clair (report.reply.screen.{section}). */
    static final Set<String> KNOWN_SECTIONS = Set.of(
            "home", "profile", "messages", "conversations", "payments", "payment", "tracking",
            "package-requests", "demandes", "envois", "announcements", "bids", "negotiations",
            "kyc", "support", "settings", "auth", "favoris", "notifications", "disputes",
            "recherche", "corridor-alerts", "onboarding", "legal", "parcels", "traveler");

    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter EN_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ReportRepository reportRepository;
    private final ReportPhotoRepository photoRepository;
    private final UserRepository userRepository;
    private final SupportTicketService supportTicketService;
    private final AuditService auditService;
    private final MessagesResolver messagesResolver;

    public AdminReportReplyService(ReportRepository reportRepository,
                                   ReportPhotoRepository photoRepository,
                                   UserRepository userRepository,
                                   SupportTicketService supportTicketService,
                                   AuditService auditService,
                                   MessagesResolver messagesResolver) {
        this.reportRepository = reportRepository;
        this.photoRepository = photoRepository;
        this.userRepository = userRepository;
        this.supportTicketService = supportTicketService;
        this.auditService = auditService;
        this.messagesResolver = messagesResolver;
    }

    /** Ticket de la conversation, et s'il vient d'être créé. */
    public record Result(SupportTicketEntity ticket, boolean created) {}

    @Transactional
    public Result reply(UUID reportId, UUID adminId, String message, List<String> attachmentKeys) {
        ReportEntity report = reportRepository.findById(reportId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "report-not-found", "Not Found", "Signalement introuvable"));
        if (report.getTargetType() != ReportTargetType.APP) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "report-not-app-bug",
                    "Unprocessable", "Seul un signalement de l'application (scarabée) peut recevoir une réponse");
        }
        UserEntity reporter = Optional.ofNullable(report.getReporterId())
                .flatMap(userRepository::findById)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "reporter-unavailable", "Unprocessable",
                        "Le signalant est inconnu ou son compte a été supprimé"));
        List<String> keys = attachmentKeys != null ? attachmentKeys : List.of();

        Optional<SupportTicketEntity> open = Optional.ofNullable(report.getSupportTicketId())
                .flatMap(supportTicketService::findTicketForAdmin)
                .filter(ticket -> !ticket.isResolved());

        Result result;
        if (open.isPresent()) {
            supportTicketService.adminReplyTakingOver(open.get().getId(), adminId, message, keys);
            result = new Result(open.get(), false);
        } else {
            Messages m = messagesResolver.of(reporter.getPreferredLanguage());
            List<String> photoKeys = photoRepository.findByReportIdOrderByCreatedAtAsc(reportId).stream()
                    .map(ReportPhotoEntity::getObjectKey)
                    .toList();
            SupportTicketEntity ticket = supportTicketService.adminStartTicketFromContext(
                    reporter.getId(), adminId, SupportCategory.OTHER.name(),
                    m.get("report.reply.subject", formatDate(m.language(), report.getCreatedAt())),
                    contextMessage(m, report), photoKeys, message, keys);
            report.setSupportTicketId(ticket.getId());
            reportRepository.save(report);
            result = new Result(ticket, true);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("adminId", adminId.toString());
        details.put("reportId", reportId.toString());
        details.put("ticketId", result.ticket().getId().toString());
        details.put("created", result.created());
        auditService.log("REPORT", reportId, "REPORT_REPLIED", adminId, details);
        return result;
    }

    /** « Votre signalement : » + description, écran concerné, date (UTC). */
    static String contextMessage(Messages m, ReportEntity report) {
        String description = report.getDescription() == null || report.getDescription().isBlank()
                ? m.get("report.reply.context.no-description")
                : truncate(report.getDescription().trim());
        StringBuilder text = new StringBuilder(m.get("report.reply.context.header"))
                .append('\n').append(description).append("\n\n");
        if (report.getScreenRoute() != null && !report.getScreenRoute().isBlank()) {
            text.append(m.get("report.reply.context.screen", screenLabel(m, report.getScreenRoute().trim())))
                    .append('\n');
        }
        LocalDateTime at = report.getCreatedAt();
        if (at != null) {
            text.append(m.get("report.reply.context.date", formatDate(m.language(), at), at.format(TIME)));
        }
        return text.toString().trim();
    }

    /**
     * Route rendue lisible par sa première section (« Profil (/profile/edit) ») ; route
     * inconnue ou vide rendue brute.
     */
    static String screenLabel(Messages m, String route) {
        String path = route.startsWith("/") ? route.substring(1) : route;
        int end = path.length();
        for (char stop : new char[]{'/', '?', '#'}) {
            int i = path.indexOf(stop);
            if (i >= 0 && i < end) end = i;
        }
        String section = path.substring(0, end);
        if (!KNOWN_SECTIONS.contains(section)) {
            return route;
        }
        return m.get("report.reply.screen." + section) + " (" + route + ")";
    }

    private static String formatDate(AppLanguage language, LocalDateTime at) {
        if (at == null) {
            at = LocalDateTime.now(java.time.ZoneOffset.UTC);
        }
        return at.format(language == AppLanguage.EN ? EN_DATE : FR_DATE);
    }

    private static String truncate(String description) {
        return description.length() <= MAX_CONTEXT_DESCRIPTION
                ? description
                : description.substring(0, MAX_CONTEXT_DESCRIPTION) + "…";
    }
}
