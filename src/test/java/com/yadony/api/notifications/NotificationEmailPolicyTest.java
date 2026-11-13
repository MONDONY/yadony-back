package com.yadony.api.notifications;

import com.yadony.api.notifications.NotificationEmailPolicy.Kind;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEmailPolicyTest {

    /**
     * Plan validé : 22 types doublés par un e-mail, 4 où l'e-mail remplace le push, plus la
     * suggestion selon les recherches (V362) et la relance de vérification d'identité, doublées
     * d'un e-mail avec désinscription, les deux types DAC7 (demande du numéro fiscal, récapitulatif annuel) et le retard annoncé d'un trajet (BA6).
     */
    @Test
    void planCounts() {
        assertThat(NotificationEmailPolicy.WITH_PUSH).hasSize(27);
        assertThat(NotificationEmailPolicy.kindOf("SEARCH_SUGGESTION", Map.of())).contains(Kind.SUGGESTION);
        assertThat(NotificationEmailPolicy.replacesPush("SEARCH_SUGGESTION", Map.of())).isFalse();
        assertThat(Kind.SUGGESTION.pref()).contains("emailAlertsTips");
        assertThat(NotificationEmailPolicy.INSTEAD_OF_PUSH).hasSize(4);
        assertThat(NotificationEmailPolicy.WITH_PUSH.keySet())
                .doesNotContainAnyElementsOf(NotificationEmailPolicy.INSTEAD_OF_PUSH.keySet());
    }

    @Test
    void corridorAlert_onlyTheDigestGoesByEmail() {
        Map<String, String> digest = Map.of("type", "CORRIDOR_ALERT", "digest", "true");
        Map<String, String> instant = Map.of("type", "CORRIDOR_ALERT", "announcementId", "x");

        assertThat(NotificationEmailPolicy.kindOf("CORRIDOR_ALERT", digest)).contains(Kind.ALERT);
        assertThat(NotificationEmailPolicy.replacesPush("CORRIDOR_ALERT", digest)).isTrue();
        assertThat(NotificationEmailPolicy.kindOf("CORRIDOR_ALERT", instant)).isEmpty();
        assertThat(NotificationEmailPolicy.replacesPush("CORRIDOR_ALERT", instant)).isFalse();
        assertThat(NotificationEmailPolicy.replacesPush("CORRIDOR_ALERT", null)).isFalse();
    }

    @Test
    void kindsAndTheirSettings() {
        assertThat(NotificationEmailPolicy.kindOf("DELIVERY_CONFIRMED", Map.of())).contains(Kind.RECAP);
        assertThat(NotificationEmailPolicy.kindOf("PAYMENT_RELEASED", Map.of())).contains(Kind.RECEIPT);
        assertThat(NotificationEmailPolicy.kindOf("wallet_topup_confirmed", Map.of())).contains(Kind.RECEIPT);
        assertThat(NotificationEmailPolicy.kindOf("NEW_MESSAGE", Map.of())).isEmpty();
        assertThat(NotificationEmailPolicy.kindOf(null, Map.of())).isEmpty();
        assertThat(NotificationEmailPolicy.replacesPush("DELIVERY_CONFIRMED", Map.of())).isFalse();
        assertThat(NotificationEmailPolicy.replacesPush("FIRST_ACTION_REMINDER", Map.of())).isTrue();

        assertThat(Kind.RECEIPT.pref()).isEmpty();
        assertThat(Kind.DECISION.pref()).isEmpty();
        assertThat(Kind.ACCOUNT.pref()).isEmpty();
        assertThat(Kind.RECAP.pref()).isEqualTo(Optional.of("emailRecaps"));
        assertThat(Kind.ALERT.pref()).isEqualTo(Optional.of("emailAlertsTips"));
        assertThat(Kind.TIP.pref()).isEqualTo(Optional.of("emailAlertsTips"));
    }

    /** Un type critique (repli SMS) garde toujours son push : l'e-mail ne peut que s'y ajouter. */
    @Test
    void criticalTypesAreNeverReplaced() {
        for (String type : NotificationTypes.CRITICAL) {
            assertThat(NotificationEmailPolicy.INSTEAD_OF_PUSH).doesNotContainKey(type);
        }
    }

    private static final Map<String, String> RELANCE = Map.of("type", "KYC_ACTION_REQUIRED", "reminder", "true");

    /** Une relance de vérification : famille des relances, e-mail en plus du push, jamais à la place. */
    @Test
    void policy_relance_estReminderAvecPush() {
        assertThat(NotificationEmailPolicy.kindOf("KYC_ACTION_REQUIRED", RELANCE)).contains(Kind.REMINDER);
        assertThat(NotificationEmailPolicy.replacesPush("KYC_ACTION_REQUIRED", RELANCE)).isFalse();
        assertThat(Kind.REMINDER.pref()).contains("emailAlertsTips");
        assertThat(NotificationEmailPolicy.WITH_PUSH)
                .containsEntry(NotificationTypes.KYC_VERIFICATION_REMINDER, Kind.REMINDER);
        assertThat(NotificationEmailPolicy.hasTypeDetail(NotificationTypes.KYC_VERIFICATION_REMINDER)).isTrue();
        assertThat(NotificationEmailPolicy.hasTypeCta(NotificationTypes.KYC_VERIFICATION_REMINDER)).isTrue();
    }

    /** Sans drapeau, KYC_ACTION_REQUIRED reste une décision : toujours envoyée, sans ligne propre. */
    @Test
    void policy_vraiRefus_resteDecision() {
        Map<String, String> refus = Map.of("type", "KYC_ACTION_REQUIRED");
        assertThat(NotificationEmailPolicy.kindOf("KYC_ACTION_REQUIRED", refus)).contains(Kind.DECISION);
        assertThat(NotificationEmailPolicy.kindOf("KYC_ACTION_REQUIRED", null)).contains(Kind.DECISION);
        assertThat(NotificationEmailPolicy.replacesPush("KYC_ACTION_REQUIRED", refus)).isFalse();
        assertThat(NotificationEmailPolicy.hasTypeDetail("KYC_ACTION_REQUIRED")).isFalse();
        assertThat(NotificationEmailPolicy.hasTypeCta("KYC_ACTION_REQUIRED")).isFalse();
        assertThat(NotificationEmailPolicy.hasTypeCta(null)).isFalse();
    }
}
