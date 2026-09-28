package com.yadony.api.admin.notifications;

import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportTargetType;
import com.yadony.api.support.SupportPriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AdminNotificationItems : libellés courts, sans donnée sensible")
class AdminNotificationItemsTest {

    private static final Instant AT = Instant.parse("2026-09-28T10:15:30Z");
    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER = UUID.fromString("99999999-8888-7777-6666-555555555555");

    /** Six chiffres d'affilée ou plus : un téléphone, un IBAN, un identifiant brut. */
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{6,}");

    private static List<AdminNotificationItem> everyKind() {
        return List.of(
                AdminNotificationItems.report(ID, AT, ReportReason.SCAM_ATTEMPT, ReportTargetType.PACKAGE_REQUEST),
                AdminNotificationItems.supportTicket(ID, AT, "PAYMENT", SupportPriority.NORMAL, "Awa", "Diallo"),
                AdminNotificationItems.supportMessage(OTHER, ID, AT, "KYC", "Awa", "Diallo"),
                AdminNotificationItems.dispute(ID, AT),
                AdminNotificationItems.noShow(ID, AT),
                AdminNotificationItems.kycInReview(ID, OTHER, AT, "Awa", "Diallo"),
                AdminNotificationItems.payoutHeld(ID, AT, new BigDecimal("45.5"), "EUR"),
                AdminNotificationItems.walletRefund(ID, AT, new BigDecimal("12000"), "XOF", "Awa", "Diallo"),
                AdminNotificationItems.gdpr(ID, AT, "Awa", "Diallo"),
                AdminNotificationItems.alert(ID, AT, "PAYOUT_FAILED_" + OTHER, "CRITICAL"));
    }

    @Test
    void chaqueEntree_porteUnIdTypeEntite_etUnLienInterne() {
        for (AdminNotificationItem item : everyKind()) {
            assertThat(item.id()).startsWith(item.type().name() + ":");
            assertThat(item.link()).startsWith("/");
            assertThat(item.createdAt()).isEqualTo(AT);
            assertThat(item.title()).isNotBlank().hasSizeLessThanOrEqualTo(60);
            assertThat(item.summary()).isNotBlank().hasSizeLessThanOrEqualTo(140);
        }
    }

    @Test
    void aucunLibelle_nePorteDeTiretCadratin_niDeCoordonnee_niDeNomComplet() {
        for (AdminNotificationItem item : everyKind()) {
            String text = item.title() + " " + item.summary();
            assertThat(text).doesNotContain("—").doesNotContain("@").doesNotContain("Diallo");
            assertThat(LONG_DIGITS.matcher(text).find()).as(text).isFalse();
        }
    }

    @Test
    void liens_suiventLesRoutesDuPanel() {
        List<AdminNotificationItem> items = everyKind();
        assertThat(items.get(0).link()).isEqualTo("/signalements");
        assertThat(items.get(1).link()).isEqualTo("/support?ticket=" + ID);
        assertThat(items.get(2).link()).isEqualTo("/support?ticket=" + ID);
        assertThat(items.get(2).id()).isEqualTo("SUPPORT_MESSAGE_RECEIVED:" + OTHER);
        assertThat(items.get(3).link()).isEqualTo("/incidents?tab=disputes");
        assertThat(items.get(4).link()).isEqualTo("/incidents?tab=noshows");
        assertThat(items.get(5).link()).isEqualTo("/kyc?status=IN_REVIEW&open=" + OTHER);
        assertThat(items.get(6).link()).isEqualTo("/transactions?held=true");
        assertThat(items.get(7).link()).isEqualTo("/transactions?tab=wallet-refunds");
        assertThat(items.get(8).link()).isEqualTo("/users/rgpd");
        assertThat(items.get(9).link()).isEqualTo("/alertes");
    }

    @Test
    void personne_prenomEtInitiale_auPlus() {
        assertThat(AdminNotificationItems.person("Awa", "Diallo")).isEqualTo("Awa D.");
        assertThat(AdminNotificationItems.person(" Awa ", null)).isEqualTo("Awa");
        assertThat(AdminNotificationItems.person(null, "Diallo")).isEqualTo("un utilisateur");
        assertThat(AdminNotificationItems.person("  ", "Diallo")).isEqualTo("un utilisateur");
        // Un prénom saisi comme un numéro ou un email ne doit jamais sortir tel quel.
        assertThat(AdminNotificationItems.person("0612345678", null)).isEqualTo("un utilisateur");
        assertThat(AdminNotificationItems.person("awa@mail.com", null)).isEqualTo("un utilisateur");
        assertThat(AdminNotificationItems.person("A".repeat(80), "B")).hasSizeLessThanOrEqualTo(30);
    }

    @Test
    void montant_formatFrancais() {
        assertThat(AdminNotificationItems.amount(new BigDecimal("45.5"), "EUR")).isEqualTo("45,50 EUR");
        assertThat(AdminNotificationItems.amount(null, "EUR")).isEqualTo("montant inconnu");
    }

    @Test
    void severites() {
        assertThat(AdminNotificationItems.alertSeverity("CRITICAL")).isEqualTo(AdminNotificationSeverity.CRITICAL);
        assertThat(AdminNotificationItems.alertSeverity("high")).isEqualTo(AdminNotificationSeverity.CRITICAL);
        assertThat(AdminNotificationItems.alertSeverity("WARNING")).isEqualTo(AdminNotificationSeverity.WARNING);
        assertThat(AdminNotificationItems.alertSeverity("warn")).isEqualTo(AdminNotificationSeverity.WARNING);
        assertThat(AdminNotificationItems.alertSeverity(null)).isEqualTo(AdminNotificationSeverity.INFO);
        assertThat(AdminNotificationItems.alertSeverity("INFO")).isEqualTo(AdminNotificationSeverity.INFO);

        assertThat(AdminNotificationItems.report(ID, AT, ReportReason.SCAM_ATTEMPT, ReportTargetType.USER).severity())
                .isEqualTo(AdminNotificationSeverity.WARNING);
        assertThat(AdminNotificationItems.report(ID, AT, ReportReason.SPAM, ReportTargetType.MESSAGE).severity())
                .isEqualTo(AdminNotificationSeverity.INFO);
        assertThat(AdminNotificationItems.report(ID, AT, null, null).summary()).isNotBlank();
        assertThat(AdminNotificationItems.supportTicket(ID, AT, "X", SupportPriority.URGENT, null, null).severity())
                .isEqualTo(AdminNotificationSeverity.WARNING);
        assertThat(AdminNotificationItems.supportTicket(ID, AT, null, null, null, null).severity())
                .isEqualTo(AdminNotificationSeverity.INFO);
        assertThat(AdminNotificationItems.dispute(ID, AT).severity()).isEqualTo(AdminNotificationSeverity.WARNING);
        assertThat(AdminNotificationItems.payoutHeld(ID, AT, BigDecimal.ONE, "EUR").severity())
                .isEqualTo(AdminNotificationSeverity.WARNING);
    }

    @Test
    void alerte_libelleSansIdentifiant() {
        assertThat(AdminNotificationItems.alertLabel("PAYOUT_FAILED_" + OTHER)).isEqualTo("PAYOUT_FAILED");
        assertThat(AdminNotificationItems.alertLabel("ESCROW_J48:" + OTHER)).isEqualTo("ESCROW_J48");
        assertThat(AdminNotificationItems.alertLabel("SENTRY_ISSUE_CREATED")).isEqualTo("SENTRY_ISSUE_CREATED");
        assertThat(AdminNotificationItems.alertLabel(null)).isEqualTo("alerte");
        assertThat(AdminNotificationItems.alertLabel(OTHER.toString())).isEqualTo("alerte");
    }

    @Test
    void categoriesSupport_connuesEtInconnues() {
        assertThat(AdminNotificationItems.supportCategory("PAYMENT")).isEqualTo("paiement");
        assertThat(AdminNotificationItems.supportCategory("payment")).isEqualTo("paiement");
        assertThat(AdminNotificationItems.supportCategory("NOPE")).isEqualTo("autre");
        assertThat(AdminNotificationItems.supportCategory(null)).isEqualTo("autre");
        for (String c : List.of("ACCOUNT", "KYC", "TRIP", "PACKAGE", "DELIVERY", "OTHER")) {
            assertThat(AdminNotificationItems.supportCategory(c)).isNotBlank();
        }
        for (ReportTargetType t : ReportTargetType.values()) {
            assertThat(AdminNotificationItems.reportTarget(t)).isNotBlank();
        }
    }
}
