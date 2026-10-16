package com.yadony.api.assistant.provider;

import com.yadony.api.assistant.llm.LlmProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderCatalogTest {

    private final AssistantLlmProviderRepository repository = mock(AssistantLlmProviderRepository.class);
    private final ProviderCatalog catalog = new ProviderCatalog(repository);

    private static AssistantLlmProviderEntity entite(String slug, int priority, Integer limite) {
        AssistantLlmProviderEntity e = new AssistantLlmProviderEntity();
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        e.setName("Fournisseur " + slug);
        e.setSlug(slug);
        e.setBaseUrl("https://" + slug + ".example.com");
        e.setModel("modele-" + slug);
        e.setApiKey("cle-en-clair-" + slug);
        e.setSupportsJsonMode(false);
        e.setDailyRequestLimit(limite);
        e.setPriority(priority);
        return e;
    }

    @Test
    void toConfigRecopieChaqueChamp() {
        AssistantLlmProviderEntity e = entite("groq", 1, 100);
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(e));

        assertThat(catalog.activeProviders()).containsExactly(new LlmProviderConfig(
                e.getId(), "groq", "https://groq.example.com", "modele-groq", "cle-en-clair-groq", false, 100));
    }

    @Test
    void conserveLOrdreRenvoyeParLeDepot() {
        AssistantLlmProviderEntity premier = entite("premier", 1, null);
        AssistantLlmProviderEntity second = entite("second", 2, null);
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(premier, second));

        assertThat(catalog.activeProviders()).extracting(LlmProviderConfig::slug)
                .containsExactly("premier", "second");
    }

    @Test
    void deuxLecturesNInterrogentQueLeDepotUneFois() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(entite("groq", 1, null)));

        catalog.activeProviders();
        catalog.activeProviders();

        verify(repository, times(1)).findByEnabledTrueOrderByPriorityAsc();
    }

    @Test
    void evictForceLaRelectureDuDepot() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(entite("groq", 1, null)));

        catalog.activeProviders();
        catalog.evict();
        catalog.activeProviders();

        verify(repository, times(2)).findByEnabledTrueOrderByPriorityAsc();
    }
}
