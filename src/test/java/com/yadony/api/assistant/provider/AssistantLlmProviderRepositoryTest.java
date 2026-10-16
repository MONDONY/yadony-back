package com.yadony.api.assistant.provider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AssistantLlmProviderRepositoryTest {

    @Autowired AssistantLlmProviderRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;

    private AssistantLlmProviderEntity provider(String slug, int priority, boolean enabled) {
        AssistantLlmProviderEntity p = new AssistantLlmProviderEntity();
        p.setName(slug); p.setSlug(slug); p.setBaseUrl("https://api.example.com/v1");
        p.setModel("m"); p.setApiKey("key_" + slug + "_1234"); p.setPriority(priority); p.setEnabled(enabled);
        return repository.saveAndFlush(p);
    }

    @Test
    void chiffreLaCleEtExclutLesSupprimes() {
        AssistantLlmProviderEntity p = new AssistantLlmProviderEntity();
        p.setName("Groq"); p.setSlug("groq"); p.setBaseUrl("https://api.groq.com/openai/v1");
        p.setModel("llama-3.3-70b-versatile"); p.setApiKey("gsk_secret_1234"); p.setPriority(1);
        repository.saveAndFlush(p);

        String stored = jdbcTemplate.queryForObject(
                "SELECT api_key_encrypted FROM assistant_llm_providers WHERE id = ?", String.class, p.getId());
        assertThat(stored).doesNotContain("gsk_secret_1234");
        assertThat(p.getApiKeyLast4()).isEqualTo("1234");
        assertThat(repository.existsBySlug("groq")).isTrue();

        p.softDelete();
        repository.saveAndFlush(p);
        assertThat(repository.findAllByOrderByPriorityAsc()).isEmpty();
        assertThat(repository.existsBySlug("groq")).isFalse();
    }

    @Test
    void relitLaCleEnClairEtTrieParPriorite() {
        AssistantLlmProviderEntity b = provider("b", 2, true);
        provider("a", 1, true);
        provider("c", 3, false);

        assertThat(repository.findAllByOrderByPriorityAsc()).extracting(AssistantLlmProviderEntity::getSlug)
                .containsExactly("a", "b", "c");
        assertThat(repository.findByEnabledTrueOrderByPriorityAsc()).extracting(AssistantLlmProviderEntity::getSlug)
                .containsExactly("a", "b");
        assertThat(repository.findById(b.getId())).get()
                .extracting(AssistantLlmProviderEntity::getApiKey).isEqualTo("key_b_1234");
    }

    @Test
    void cleCourteGardeToutCommeDerniersCaracteres() {
        AssistantLlmProviderEntity p = new AssistantLlmProviderEntity();
        p.setApiKey("ab");
        assertThat(p.getApiKeyLast4()).isEqualTo("ab");
    }
}
