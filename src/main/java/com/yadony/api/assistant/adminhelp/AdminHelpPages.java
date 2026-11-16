package com.yadony.api.assistant.adminhelp;

import com.yadony.api.admin.account.AdminPermission;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pages de la console vers lesquelles une fiche peut pointer, et la permission que chacune exige (miroir des
 * {@code definePageMeta} de dony-admin). Une route de fiche vers une page absente d'ici fait échouer le
 * démarrage : une fiche ne peut pas envoyer vers un écran inventé.
 */
final class AdminHelpPages {

    /** Pages sans permission : la console entière y a accès. */
    static final Set<String> OPEN = Set.of("/aide", "/change-password", "/mfa-setup");

    private static final Map<String, AdminPermission> PAGES = new LinkedHashMap<>();

    static {
        PAGES.put("/", AdminPermission.METRICS_VIEW);
        PAGES.put("/users", AdminPermission.USER_VIEW);
        PAGES.put("/users/rgpd", AdminPermission.USER_GDPR_DELETE);
        PAGES.put("/kyc", AdminPermission.USER_KYC);
        PAGES.put("/gp-pro", AdminPermission.GP_PRO_REVIEW);
        PAGES.put("/transactions", AdminPermission.PAYMENT_VIEW);
        PAGES.put("/promo", AdminPermission.PROMO_MANAGE);
        PAGES.put("/topup-codes", AdminPermission.TOPUP_CODE_MANAGE);
        PAGES.put("/ajustements-solde", AdminPermission.WALLET_ADJUST);
        PAGES.put("/tarification", AdminPermission.CONFIG_MANAGE);
        PAGES.put("/dac7", AdminPermission.DAC7_REPORT);
        PAGES.put("/colis", AdminPermission.BID_VIEW);
        PAGES.put("/incidents", AdminPermission.DISPUTE_VIEW);
        PAGES.put("/alertes", AdminPermission.ALERT_VIEW);
        PAGES.put("/signalements", AdminPermission.REPORT_VIEW);
        PAGES.put("/support", AdminPermission.SUPPORT_TICKET_VIEW);
        PAGES.put("/moderation", AdminPermission.MODERATION_VIEW);
        PAGES.put("/assistant", AdminPermission.ASSISTANT_MANAGE);
        PAGES.put("/communications", AdminPermission.NOTIFICATION_SEND);
        PAGES.put("/modals-video", AdminPermission.NOTIFICATION_SEND);
        PAGES.put("/modals-video/nouveau", AdminPermission.NOTIFICATION_SEND);
        PAGES.put("/audit", AdminPermission.AUDIT_VIEW);
        PAGES.put("/exports", AdminPermission.EXPORT_RUN);
        PAGES.put("/parametres", AdminPermission.CONFIG_MANAGE);
        PAGES.put("/administrateurs", AdminPermission.ADMIN_MANAGE);
    }

    private AdminHelpPages() {
    }

    /** Chemin sans sa requête ({@code /colis?view=ACTIVE} donne {@code /colis}). */
    static String pathOf(String route) {
        int q = route.indexOf('?');
        return q < 0 ? route : route.substring(0, q);
    }

    static boolean known(String route) {
        String path = pathOf(route);
        return OPEN.contains(path) || PAGES.containsKey(path);
    }

    /** Permission exigée par la page de la route ; vide pour une page ouverte ou inconnue. */
    static Optional<AdminPermission> permission(String route) {
        return Optional.ofNullable(PAGES.get(pathOf(route)));
    }

    /** Vrai si l'admin qui a ces permissions peut ouvrir la route. */
    static boolean allowed(String route, Set<String> permissions) {
        return permission(route).map(p -> permissions.contains(p.name())).orElse(true);
    }
}
