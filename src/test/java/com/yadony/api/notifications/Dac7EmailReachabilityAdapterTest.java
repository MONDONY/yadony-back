package com.yadony.api.notifications;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Récapitulatif DAC7 : un voyageur n'est joignable par e-mail que si l'envoi d'e-mails est allumé")
class Dac7EmailReachabilityAdapterTest {

    private final UserRepository users = mock(UserRepository.class);
    private final FirebaseContactService contacts = mock(FirebaseContactService.class);
    private final UUID userId = UUID.randomUUID();

    private void withEmail(String email) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-1");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(contacts.getContact("uid-1")).thenReturn(new FirebaseContactService.Contact("+33600000000", email));
    }

    @Test
    @DisplayName("e-mails allumés : vrai avec une adresse, faux sans adresse ou sur erreur de lecture")
    void allume() {
        Dac7EmailReachabilityAdapter adapter = new Dac7EmailReachabilityAdapter(users, contacts, true);
        withEmail("awa@example.test");
        assertThat(adapter.hasEmail(userId)).isTrue();
        withEmail("  ");
        assertThat(adapter.hasEmail(userId)).isFalse();
        when(contacts.getContact(any())).thenThrow(new IllegalStateException("firebase"));
        assertThat(adapter.hasEmail(userId)).isFalse();
    }

    @Test
    @DisplayName("interrupteur yadony.notifications.email.enabled coupé : faux, sans même lire le contact")
    void eteint() {
        Dac7EmailReachabilityAdapter adapter = new Dac7EmailReachabilityAdapter(users, contacts, false);
        withEmail("awa@example.test");
        assertThat(adapter.hasEmail(userId)).isFalse();
        verify(contacts, never()).getContact(any());
    }
}
