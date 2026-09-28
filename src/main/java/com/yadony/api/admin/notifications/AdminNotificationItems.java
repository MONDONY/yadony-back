package com.yadony.api.admin.notifications;

import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportTargetType;
import com.yadony.api.support.SupportPriority;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Libellés et liens des entrées du fil. Règles : français, courts, jamais de coordonnée ni de
 * texte libre saisi par un utilisateur (sujet de ticket, description de signalement, contenu de
 * message peuvent contenir un numéro), un prénom et une initiale au plus, pas de tiret cadratin.
 *
 * <p>Les liens visent les routes du panel ({@code dony-admin/app/pages}). {@code ?ticket=} sur
 * {@code /support} et {@code ?tab=} sur {@code /incidents} et {@code /transactions} sont lus par
 * la PR jumelle du panel ; une page qui les ignore s'ouvre simplement sur son onglet par défaut.
 */
final class AdminNotificationItems {

    static final String SOMEONE = "un utilisateur";

    private static final int MAX_FIRST_NAME = 24;
    /** Un chiffre ou un @ dans un prénom : saisie d'un numéro ou d'un email, jamais affichée. */
    private static final Pattern NOT_A_NAME = Pattern.compile(".*[\\d@].*");
    private static final Pattern TRAILING_ID =
            Pattern.compile("[_:\\-]?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Set<ReportReason> SERIOUS_REASONS =
            Set.of(ReportReason.SCAM_ATTEMPT, ReportReason.PROHIBITED_ITEM, ReportReason.HARASSMENT);

    private static final Map<String, String> SUPPORT_CATEGORIES = Map.of(
            "ACCOUNT", "compte",
            "KYC", "vérification d'identité",
            "PAYMENT", "paiement",
            "TRIP", "trajet",
            "PACKAGE", "colis",
            "DELIVERY", "livraison",
            "OTHER", "autre");

    private AdminNotificationItems() {
    }

    // ---------------------------------------------------------------- entrées

    static AdminNotificationItem report(UUID id, Instant at, ReportReason reason, ReportTargetType target) {
        String motive = reason == null ? "motif non précisé" : reason.label();
        String summary = target == null ? motive : motive + " (" + reportTarget(target) + ")";
        AdminNotificationSeverity severity = reason != null && SERIOUS_REASONS.contains(reason)
                ? AdminNotificationSeverity.WARNING : AdminNotificationSeverity.INFO;
        return item(AdminNotificationType.REPORT_CREATED, id, "Nouveau signalement", summary, severity, at,
                "/signalements");
    }

    static AdminNotificationItem supportTicket(UUID id, Instant at, String category, SupportPriority priority,
                                               String first, String last) {
        AdminNotificationSeverity severity = priority == SupportPriority.HIGH || priority == SupportPriority.URGENT
                ? AdminNotificationSeverity.WARNING : AdminNotificationSeverity.INFO;
        return item(AdminNotificationType.SUPPORT_TICKET_CREATED, id, "Nouveau ticket support",
                "Ticket " + supportCategory(category) + " ouvert par " + person(first, last), severity, at,
                supportLink(id));
    }

    static AdminNotificationItem supportMessage(UUID messageId, UUID ticketId, Instant at, String category,
                                                String first, String last) {
        return item(AdminNotificationType.SUPPORT_MESSAGE_RECEIVED, messageId, "Nouveau message support",
                capitalize(person(first, last)) + " a répondu sur un ticket " + supportCategory(category),
                AdminNotificationSeverity.INFO, at, supportLink(ticketId));
    }

    static AdminNotificationItem dispute(UUID id, Instant at) {
        return item(AdminNotificationType.DISPUTE_OPENED, id, "Litige ouvert",
                "Un litige a été ouvert sur un colis", AdminNotificationSeverity.WARNING, at,
                "/incidents?tab=disputes");
    }

    static AdminNotificationItem noShow(UUID id, Instant at) {
        return item(AdminNotificationType.NOSHOW_PENDING, id, "No-show à confirmer",
                "Une absence à la remise attend une décision", AdminNotificationSeverity.WARNING, at,
                "/incidents?tab=noshows");
    }

    static AdminNotificationItem kycInReview(UUID verificationId, UUID userId, Instant at, String first, String last) {
        return item(AdminNotificationType.KYC_IN_REVIEW, verificationId, "Vérification d'identité à examiner",
                "Dossier de " + person(first, last) + " soumis", AdminNotificationSeverity.INFO, at,
                "/kyc?status=IN_REVIEW&open=" + userId);
    }

    static AdminNotificationItem payoutHeld(UUID id, Instant at, BigDecimal amount, String currency) {
        return item(AdminNotificationType.PAYOUT_HELD, id, "Versement retenu",
                "Paiement de " + amount(amount, currency) + " : bénéficiaire gelé, versement bloqué",
                AdminNotificationSeverity.WARNING, at, "/transactions?held=true");
    }

    static AdminNotificationItem walletRefund(UUID id, Instant at, BigDecimal amount, String currency,
                                              String first, String last) {
        return item(AdminNotificationType.WALLET_REFUND_REQUESTED, id, "Remboursement wallet demandé",
                amount(amount, currency) + " à rembourser à " + person(first, last),
                AdminNotificationSeverity.INFO, at, "/transactions?tab=wallet-refunds");
    }

    static AdminNotificationItem gdpr(UUID userId, Instant at, String first, String last) {
        return item(AdminNotificationType.GDPR_REQUESTED, userId, "Demande de suppression RGPD",
                capitalize(person(first, last)) + " demande la suppression de son compte",
                AdminNotificationSeverity.INFO, at, "/users/rgpd");
    }

    static AdminNotificationItem alert(UUID id, Instant at, String type, String severity) {
        return item(AdminNotificationType.ADMIN_ALERT, id, "Alerte plateforme",
                "Alerte " + alertLabel(type) + " non résolue", alertSeverity(severity), at, "/alertes");
    }

    // ---------------------------------------------------------------- briques

    /** Prénom et initiale du nom ; jamais le nom complet, jamais une saisie qui ressemble à une coordonnée. */
    static String person(String first, String last) {
        if (first == null || first.isBlank()) {
            return SOMEONE;
        }
        String name = first.strip();
        if (NOT_A_NAME.matcher(name).matches()) {
            return SOMEONE;
        }
        if (name.length() > MAX_FIRST_NAME) {
            name = name.substring(0, MAX_FIRST_NAME);
        }
        if (last == null || last.isBlank() || NOT_A_NAME.matcher(last.strip()).matches()) {
            return name;
        }
        return name + " " + Character.toUpperCase(last.strip().charAt(0)) + ".";
    }

    static String amount(BigDecimal amount, String currency) {
        if (amount == null) {
            return "montant inconnu";
        }
        String value = amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
        return currency == null ? value : value + " " + currency;
    }

    /** {@code admin_alerts.severity} est un texte libre (INFO par défaut) : ramené aux trois niveaux. */
    static AdminNotificationSeverity alertSeverity(String raw) {
        if (raw == null) {
            return AdminNotificationSeverity.INFO;
        }
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "HIGH", "ERROR", "INCIDENT" -> AdminNotificationSeverity.CRITICAL;
            case "WARNING", "WARN", "MEDIUM", "AVERTISSEMENT" -> AdminNotificationSeverity.WARNING;
            default -> AdminNotificationSeverity.INFO;
        };
    }

    /** Code de l'alerte sans l'identifiant d'entité que certains appelants y suffixent. */
    static String alertLabel(String type) {
        if (type == null) {
            return "alerte";
        }
        String label = TRAILING_ID.matcher(type.strip()).replaceAll("");
        return label.isBlank() ? "alerte" : label;
    }

    static String supportCategory(String category) {
        if (category == null) {
            return "autre";
        }
        return SUPPORT_CATEGORIES.getOrDefault(category.strip().toUpperCase(Locale.ROOT), "autre");
    }

    static String reportTarget(ReportTargetType target) {
        return switch (target) {
            case USER -> "profil";
            case ANNOUNCEMENT -> "trajet";
            case BID -> "colis";
            case MESSAGE -> "message";
            case RATING -> "avis";
            case APP -> "application";
            case PACKAGE_REQUEST -> "demande d'envoi";
        };
    }

    private static String supportLink(UUID ticketId) {
        return "/support?ticket=" + ticketId;
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static AdminNotificationItem item(AdminNotificationType type, UUID entityId, String title,
                                              String summary, AdminNotificationSeverity severity, Instant at,
                                              String link) {
        return new AdminNotificationItem(type.name() + ":" + entityId, type, title, summary, severity, at, link);
    }
}
