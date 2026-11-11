package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.dac7.events.Dac7TaxInfoRequestedEvent;
import com.yadony.api.dac7.events.Dac7TaxStatementEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Dac7NotificationListenerTest {

    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
    private final Dac7NotificationListener listener = new Dac7NotificationListener(dispatcher);
    private final UUID user = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private Map<String, String> sentData(String expectedTitle) {
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).notifyUser(eq(user), eq(expectedTitle), anyString(), data.capture());
        return data.getValue();
    }

    @Test
    void demande_ouvreLEcranInformationsFiscales() {
        when(dispatcher.messagesFor(user)).thenReturn(TestMessages.fr());
        listener.onTaxInfoRequested(new Dac7TaxInfoRequestedEvent(user, "REQUEST"));
        assertThat(sentData("Informations fiscales")).containsEntry("type", "TAX_INFO_REQUEST")
                .containsEntry("route", "/profile/tax-info").containsEntry("reminder", "false");
    }

    @Test
    void paysInconnu_seulLePaysEstDemande() {
        when(dispatcher.messagesFor(user)).thenReturn(TestMessages.fr());
        listener.onTaxInfoRequested(new Dac7TaxInfoRequestedEvent(user, "REQUEST",
                Dac7TaxInfoRequestedEvent.SCOPE_RESIDENCE_COUNTRY));
        assertThat(sentData("Votre pays de résidence")).containsEntry("type", "TAX_INFO_REQUEST")
                .containsEntry("route", "/profile/tax-info").containsEntry("scope", "RESIDENCE_COUNTRY")
                .containsEntry("reminder", "false");
    }

    @Test
    void porteeAbsente_informationsFiscales() {
        when(dispatcher.messagesFor(user)).thenReturn(TestMessages.fr());
        listener.onTaxInfoRequested(new Dac7TaxInfoRequestedEvent(user, "REQUEST", null));
        assertThat(sentData("Informations fiscales")).containsEntry("scope", "TAX_INFO");
    }

    @Test
    void rappel_texteDeRappel() {
        when(dispatcher.messagesFor(user)).thenReturn(TestMessages.en());
        listener.onTaxInfoRequested(new Dac7TaxInfoRequestedEvent(user, "REMINDER_2"));
        assertThat(sentData("Reminder: tax number")).containsEntry("reminder", "true")
                .containsEntry("step", "REMINDER_2");
    }

    @Test
    void recapitulatif_detailComplet() {
        when(dispatcher.messagesFor(user)).thenReturn(TestMessages.fr());
        listener.onTaxStatement(new Dac7TaxStatementEvent(user, 2026, List.of(
                new Dac7TaxStatementEvent.Quarter(1, 95, 1, 5), new Dac7TaxStatementEvent.Quarter(2, 0, 0, 0),
                new Dac7TaxStatementEvent.Quarter(3, 0, 0, 0), new Dac7TaxStatementEvent.Quarter(4, 10, 1, 1))));
        ArgumentCaptor<String> push = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        verify(dispatcher).notifyUserWithEmailDetail(eq(user), eq("Récapitulatif fiscal 2026"), push.capture(),
                email.capture(), any());
        // Push : court, aucun montant ; e-mail : le détail.
        assertThat(push.getValue()).hasSizeLessThanOrEqualTo(NotificationCaps.BODY_MAX)
                .isEqualTo("Votre récapitulatif fiscal 2026 est disponible.").doesNotContain("€").doesNotContain("95");
        assertThat(email.getValue()).contains("Trimestre 1 : 95 €").contains("Total 2026 : 105 €");
    }

    @Test
    void echecDEnvoi_avale() {
        when(dispatcher.messagesFor(user)).thenReturn(TestMessages.fr());
        doThrow(new IllegalStateException("x")).when(dispatcher).notifyUser(any(), any(), any(), any());
        doThrow(new IllegalStateException("x")).when(dispatcher).notifyUserWithEmailDetail(any(), any(), any(), any(),
                any());
        listener.onTaxInfoRequested(new Dac7TaxInfoRequestedEvent(user, "REQUEST"));
        listener.onTaxStatement(new Dac7TaxStatementEvent(user, 2026, List.of()));
    }

    @Test
    void classement_routes_preferences_email() {
        assertThat(NotificationCategory.fromType("TAX_INFO_REQUEST")).isEqualTo(NotificationCategory.ANNONCE);
        assertThat(NotificationCategory.fromType("TAX_STATEMENT")).isEqualTo(NotificationCategory.ANNONCE);
        assertThat(NotificationDeeplink.of("TAX_INFO_REQUEST", Map.of())).contains("yadony://profile/tax-info");
        assertThat(NotificationDeeplink.of("TAX_STATEMENT", Map.of())).contains("yadony://profile/tax-info");
        assertThat(NotificationPrefsService.isAlwaysOn("TAX_INFO_REQUEST")).isFalse();
        assertThat(NotificationPrefsService.isAlwaysOn("TAX_STATEMENT")).isTrue();
        assertThat(NotificationEmailPolicy.kindOf("TAX_INFO_REQUEST", Map.of()))
                .contains(NotificationEmailPolicy.Kind.TAX_REQUEST);
        assertThat(NotificationEmailPolicy.Kind.TAX_REQUEST.pref()).contains("emailAlertsTips");
        assertThat(NotificationEmailPolicy.kindOf("TAX_STATEMENT", Map.of()))
                .contains(NotificationEmailPolicy.Kind.TAX_STATEMENT);
        assertThat(NotificationEmailPolicy.Kind.TAX_STATEMENT.pref()).isEmpty();
    }
}
