package com.yadony.api.tracking;

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
        assertThat(ParcelSteps.currentStep(List.of())).isEqualTo("En attente du scan de départ");
    }

    @Test
    void depart_seul_enRoute_sansTiretCadratin() {
        String step = ParcelSteps.currentStep(List.of(event(TrackingEventType.DEPART)));
        assertThat(step).isEqualTo("Remis au voyageur, en route");
        assertThat(step).doesNotContain("—");
    }

    @Test
    void transit_lEmporteSurDepart() {
        assertThat(ParcelSteps.currentStep(List.of(event(TrackingEventType.DEPART), event(TrackingEventType.TRANSIT))))
                .isEqualTo("En transit");
    }

    @Test
    void arrivee_lEmporteSurTout() {
        assertThat(ParcelSteps.currentStep(List.of(event(TrackingEventType.TRANSIT), event(TrackingEventType.ARRIVEE))))
                .isEqualTo("Livraison confirmée");
    }

    @Test
    void libelles() {
        assertThat(ParcelSteps.label(TrackingEventType.DEPART)).isEqualTo("Colis remis au voyageur");
        assertThat(ParcelSteps.label(TrackingEventType.TRANSIT)).isEqualTo("En transit");
        assertThat(ParcelSteps.label(TrackingEventType.ARRIVEE)).isEqualTo("Arrivée confirmée");
    }
}
