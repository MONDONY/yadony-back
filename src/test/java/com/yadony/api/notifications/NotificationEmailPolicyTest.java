package com.yadony.api.notifications;

import com.yadony.api.notifications.NotificationEmailPolicy.Kind;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEmailPolicyTest {

    /** Plan validé : 22 types doublés par un e-mail, 5 où l'e-mail remplace le push. */
    @Test
    void planCounts() {
        assertThat(NotificationEmailPolicy.WITH_PUSH).hasSize(22);
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
}
