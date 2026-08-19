package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le motif de retrait servait a la fois de note interne d'audit et de corps de notification.
 * Un moderateur ecrivant « signale par Awa Ndiaye, ticket #4821 » nommait le signalant aupres
 * de la personne sanctionnee.
 *
 * <p>Ce test verrouille la separation : quoi que contienne la note interne, elle ne doit
 * jamais atteindre le voyageur. La verification porte sur le CATALOGUE lui-meme — c'est lui
 * qui garantit la propriete, independamment de ce que le moderateur saisit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementRemovalReason — le libelle public ne fuite jamais l'interne")
class AnnouncementRemovalReasonSeparationTest {

    @Test
    @DisplayName("Chaque motif a un libelle public non vide et lisible par un utilisateur")
    void everyReasonHasAReadablePublicLabel() {
        for (AnnouncementRemovalReason reason : AnnouncementRemovalReason.values()) {
            assertThat(reason.publicLabel())
                    .as("libellé public de %s", reason)
                    .isNotBlank()
                    // Un libellé qui serait resté le nom de la constante trahirait un oubli de
                    // traduction et exposerait un jargon interne au voyageur.
                    .isNotEqualTo(reason.name());
        }
    }

    @Test
    @DisplayName("Aucun libelle public ne contient de marqueur de note interne")
    void noPublicLabelLeaksInternalMarkers() {
        for (AnnouncementRemovalReason reason : AnnouncementRemovalReason.values()) {
            String label = reason.publicLabel().toLowerCase();
            assertThat(label)
                    .as("libellé public de %s", reason)
                    .doesNotContain("ticket")
                    .doesNotContain("signalé par")
                    .doesNotContain("signale par");
        }
    }

    @Test
    @DisplayName("OTHER reste vague : c'est le filet, il est lu par la personne sanctionnee")
    void otherStaysVague() {
        assertThat(AnnouncementRemovalReason.OTHER.publicLabel())
                .isEqualTo("Non conforme aux conditions d'utilisation");
    }
}
