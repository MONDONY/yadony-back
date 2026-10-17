package com.yadony.api.assistant.parcel;

import com.yadony.api.common.assistant.ParcelDirectory;
import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelLink;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantParcelFlowTest {

    private final ParcelDirectory directory = mock(ParcelDirectory.class);
    private final AssistantLookupGuard guard = new AssistantLookupGuard();
    private final UUID bid = UUID.randomUUID();

    private ParcelRef parcel(ParcelRole role, String number) {
        return new ParcelRef(bid, UUID.randomUUID(), role, "ARRIVED", number, null, null, null, true);
    }

    private AssistantParcelFlow flow(ParcelFacts... facts) {
        return new AssistantParcelFlow(directory, java.util.Arrays.stream(facts)
                .map(f -> (com.yadony.api.common.assistant.ParcelFactsContributor) p -> f).toList(), guard);
    }

    @Test
    void numeroEcritPasseAvantLeColisChoisi() {
        ParcelRef p = parcel(ParcelRole.SENDER, "DON-7K3M9QXA");
        when(directory.byTrackingNumber("uid", "DON-7K3M9QXA")).thenReturn(Optional.of(p));
        AssistantParcelFlow.Lookup l = flow().lookup("uid", "DON-7K3M9QXA", UUID.randomUUID());
        assertThat(l.parcel()).isEqualTo(p);
        assertThat(l.byNumber()).isTrue();
        assertThat(l.incorrect()).isFalse();
    }

    @Test
    void apresDixNumerosFauxPlusAucuneRecherche() {
        AssistantParcelFlow flow = flow();
        for (int i = 0; i < AssistantLookupGuard.MAX_FAILED_LOOKUPS_PER_DAY; i++) {
            assertThat(flow.lookup("uid", "DON-ZZZZ999" + (i % 10), null).incorrect()).isTrue();
        }
        ParcelRef p = parcel(ParcelRole.SENDER, "DON-7K3M9QXA");
        when(directory.byTrackingNumber("uid", "DON-7K3M9QXA")).thenReturn(Optional.of(p));
        assertThat(flow.lookup("uid", "DON-7K3M9QXA", null).incorrect()).isTrue();
        verify(directory, never()).byTrackingNumber("uid", "DON-7K3M9QXA");
    }

    @Test
    void sansNumeroNiColisChoisiRienACherche() {
        AssistantParcelFlow.Lookup l = flow().lookup("uid", "où est mon colis ?", null);
        assertThat(l.parcel()).isNull();
        assertThat(l.incorrect()).isFalse();
        verify(directory, never()).byTrackingNumber(anyString(), anyString());
    }

    @Test
    void unPackageEnPanneNEmpechePasLesAutres() {
        AssistantParcelFlow flow = new AssistantParcelFlow(directory, List.of(
                p -> ParcelFacts.of(List.of("a")),
                p -> { throw new IllegalStateException("panne"); },
                p -> new ParcelFacts(List.of("b"), Set.of(ParcelLink.SUPPORT))), guard);
        ParcelFacts f = flow.facts(parcel(ParcelRole.SENDER, null));
        assertThat(f.lines()).containsExactly("a", "b");
        assertThat(f.links()).containsExactly(ParcelLink.SUPPORT);
    }

    @Test
    void regenerationProposeeSeulementALExpediteur() {
        ParcelFacts f = new ParcelFacts(List.of(), Set.of(ParcelLink.RENEW_PICKUP_CODE, ParcelLink.DISPUTES));
        assertThat(flow().actions(parcel(ParcelRole.SENDER, null), f, "fr")).containsOnlyKeys(
                AssistantParcelFlow.OPEN_TOKEN, AssistantParcelFlow.NEW_CODE_TOKEN, "/disputes");
        assertThat(flow().actions(parcel(ParcelRole.TRAVELER, null), f, "en").keySet())
                .containsExactly(AssistantParcelFlow.OPEN_TOKEN, "/disputes");
        assertThat(flow().actions(parcel(ParcelRole.TRAVELER, null), f, "en").get("/disputes").label())
                .isEqualTo("My disputes");
    }

    @Test
    void leDestinataireOuvreSaReception() {
        assertThat(flow().open(parcel(ParcelRole.RECIPIENT, null), "fr").route()).isEqualTo("/receptions/" + bid);
        assertThat(flow().open(parcel(ParcelRole.RECIPIENT, null), "fr").label()).isEqualTo("Voir la réception");
        assertThat(flow().open(parcel(ParcelRole.TRAVELER, null), "en").label()).isEqualTo("Open the parcel");
    }

    @Test
    void etapeSansNumeroNiTrajet() {
        assertThat(AssistantParcelFlow.status(parcel(ParcelRole.TRAVELER, null), "fr"))
                .isEqualTo("Colis : arrivé à destination, en attente de retrait. Ouvrez sa fiche pour le détail.");
    }

    @Test
    void choixReprendLesChampsDuColis() {
        var c = AssistantParcelFlow.choice(parcel(ParcelRole.RECIPIENT, "DON-7K3M9QXA"));
        assertThat(c.parcelId()).isEqualTo(bid);
        assertThat(c.role()).isEqualTo("RECIPIENT");
        assertThat(c.trackingNumber()).isEqualTo("DON-7K3M9QXA");
    }
}
