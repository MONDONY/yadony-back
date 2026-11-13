package com.yadony.api.tracking;

import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParcelStepsTest {

    private static TrackingEventEntity event(TrackingEventType type) {
        TrackingEventEntity e = new TrackingEventEntity();
        e.setEventType(type);
        return e;
    }

    @Test
    void aucunEvenement_attenteDuDepart() {
        assertThat(ParcelSteps.currentStep(List.of(), null)).isEqualTo("En attente du scan de départ");
    }

    @Test
    void depart_seul_enRoute_sansTiretCadratin() {
        String step = ParcelSteps.currentStep(List.of(event(TrackingEventType.DEPART)), null);
        assertThat(step).isEqualTo("Remis au voyageur, en route");
        assertThat(step).doesNotContain("—");
    }

    @Test
    void transit_lEmporteSurDepart() {
        assertThat(ParcelSteps.currentStep(List.of(event(TrackingEventType.DEPART), event(TrackingEventType.TRANSIT)), null))
                .isEqualTo("En transit");
    }

    @Test
    void arrivee_lEmporteSurTout() {
        assertThat(ParcelSteps.currentStep(List.of(event(TrackingEventType.TRANSIT), event(TrackingEventType.ARRIVEE)), null))
                .isEqualTo("Livraison confirmée");
    }

    @Test
    void libelles() {
        assertThat(ParcelSteps.label(TrackingEventType.DEPART, null)).isEqualTo("Colis remis au voyageur");
        assertThat(ParcelSteps.label(TrackingEventType.TRANSIT, null)).isEqualTo("En transit");
        assertThat(ParcelSteps.label(TrackingEventType.ARRIVEE, null)).isEqualTo("Arrivée confirmée");
    }

    @Test
    void bateau_departEstUnDepotALEntrepot() {
        assertThat(ParcelSteps.label(TrackingEventType.DEPART, TransportMode.BOAT)).isEqualTo("Dépôt à l'entrepôt");
        assertThat(ParcelSteps.currentStep(List.of(event(TrackingEventType.DEPART)), TransportMode.BOAT))
                .isEqualTo("Déposé à l'entrepôt");
        // Les étapes suivantes restent communes à tous les modes.
        assertThat(ParcelSteps.label(TrackingEventType.TRANSIT, TransportMode.BOAT)).isEqualTo("En transit");
        assertThat(ParcelSteps.label(TrackingEventType.ARRIVEE, TransportMode.BOAT)).isEqualTo("Arrivée confirmée");
    }

    @Test
    void autresModesEtModeInconnu_gardentLesLibellesNeutres() {
        for (TransportMode mode : new TransportMode[] {TransportMode.PLANE, TransportMode.CAR, TransportMode.BUS,
                TransportMode.TRAIN, TransportMode.OTHER, null}) {
            assertThat(ParcelSteps.label(TrackingEventType.DEPART, mode)).as("%s", mode)
                    .isEqualTo("Colis remis au voyageur");
            assertThat(ParcelSteps.currentStep(List.of(event(TrackingEventType.DEPART)), mode)).as("%s", mode)
                    .isEqualTo("Remis au voyageur, en route");
        }
    }
}
