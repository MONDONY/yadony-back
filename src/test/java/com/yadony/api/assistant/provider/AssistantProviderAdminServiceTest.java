package com.yadony.api.assistant.provider;

import com.yadony.api.assistant.llm.LlmCallException;
import com.yadony.api.assistant.llm.OpenAiCompatibleClient;
import com.yadony.api.assistant.llm.ProviderHealthRegistry;
import com.yadony.api.assistant.provider.dto.AdminProviderResponse;
import com.yadony.api.assistant.provider.dto.CreateProviderRequest;
import com.yadony.api.assistant.provider.dto.ProviderTestResponse;
import com.yadony.api.assistant.provider.dto.UpdateProviderRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AssistantProviderAdminServiceTest {

    private static final UUID ADMIN = UUID.randomUUID();

    @Autowired AssistantProviderAdminService service;
    @Autowired AssistantLlmProviderRepository repository;
    @Autowired ProviderCatalog catalog;
    @Autowired ProviderHealthRegistry health;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean AuditService auditService;
    @MockitoBean OpenAiCompatibleClient client;

    /**
     * Le service ne vide le catalogue et la santé qu'après le commit : le test (rollback par défaut)
     * valide donc la transaction, puis en rouvre une. {@link #clean()} supprime ce qui a été validé.
     */
    private void commit() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
    }

    @AfterEach
    void clean() {
        // Hors transaction de test (sinon la suppression serait annulée avec elle) : ce qu'un test a validé part ici.
        if (TestTransaction.isActive()) TestTransaction.end();
        jdbcTemplate.update("DELETE FROM assistant_llm_providers");
        catalog.evict();
    }

    private CreateProviderRequest create(String name) {
        return new CreateProviderRequest(name, "https://api.groq.com/openai/v1/", "llama", "gsk_secret_1234", true, 1000, true);
    }

    private UpdateProviderRequest sameAs(String model, String apiKey) {
        return new UpdateProviderRequest("Groq", "https://api.groq.com/openai/v1", model, apiKey, true, 1000, true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> violations(YadonyBusinessException e) {
        return (Map<String, Object>) e.getProperties().get("violations");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditPayload(UUID id, String action) {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("assistant_llm_provider"), eq(id), eq(action), eq(ADMIN), payload.capture());
        return payload.getValue();
    }

    @Test
    void creeAvecSlugPrioriteEtCleMasquee() {
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        AdminProviderResponse b = service.create(create("Groq"), ADMIN);
        assertThat(a.slug()).isEqualTo("groq");
        assertThat(b.slug()).isEqualTo("groq-2");
        assertThat(a.priority()).isEqualTo(1);
        assertThat(b.priority()).isEqualTo(2);
        assertThat(a.baseUrl()).isEqualTo("https://api.groq.com/openai/v1");
        assertThat(a.apiKeyLast4()).isEqualTo("1234");
        Map<String, Object> payload = auditPayload(a.id(), "ASSISTANT_PROVIDER_CREATED");
        assertThat(payload.toString()).doesNotContain("gsk_secret_1234").doesNotContain("1234");
        assertThat(payload).containsEntry("slug", "groq").doesNotContainKey("name");
    }

    @Test
    void refuseUneAdresseInterne() {
        CreateProviderRequest bad = new CreateProviderRequest("X", "https://127.0.0.1/v1", "m", "cle-secrete", true, null, true);
        assertThatThrownBy(() -> service.create(bad, ADMIN)).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(422);
            assertThat(violations(e)).containsKey("baseUrl");
        });
    }

    @Test
    void modificationSansCleGardeLaCle() {
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        service.update(a.id(), new UpdateProviderRequest("Groq rapide", "https://api.groq.com/openai/v1", "llama-2",
                null, false, null, false), ADMIN);
        AssistantLlmProviderEntity e = repository.findById(a.id()).orElseThrow();
        assertThat(e.getApiKey()).isEqualTo("gsk_secret_1234");
        assertThat(e.getName()).isEqualTo("Groq rapide");
        assertThat(e.getSlug()).isEqualTo("groq");
        assertThat(e.getDailyRequestLimit()).isNull();
        assertThat(e.isEnabled()).isFalse();
        assertThat(auditPayload(a.id(), "ASSISTANT_PROVIDER_UPDATED")).containsEntry("apiKeyChanged", false);
    }

    @Test
    void changerLAdresseSansRessaisirLaCleEstRefuse() {
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        assertThatThrownBy(() -> service.update(a.id(), new UpdateProviderRequest("Groq", "https://autre-serveur.com/v1",
                "llama", null, true, 1000, true), ADMIN)).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(422);
            assertThat(violations(e)).containsKey("apiKey");
        });
        assertThat(repository.findById(a.id()).orElseThrow().getBaseUrl()).isEqualTo("https://api.groq.com/openai/v1");
    }

    @Test
    void refusSansCleNeModifiePasLEntite() {
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        assertThatThrownBy(() -> service.update(a.id(), new UpdateProviderRequest("Autre nom", "https://autre-serveur.com/v1",
                "autre-modele", "  ", false, 5, false), ADMIN)).isInstanceOf(YadonyBusinessException.class);
        AssistantLlmProviderEntity e = repository.findById(a.id()).orElseThrow();
        assertThat(e.getName()).isEqualTo("Groq");
        assertThat(e.getModel()).isEqualTo("llama");
        assertThat(e.isSupportsJsonMode()).isTrue();
        assertThat(e.getDailyRequestLimit()).isEqualTo(1000);
        assertThat(e.isEnabled()).isTrue();
    }

    @Test
    void modificationAvecNouvelleCle() {
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        AdminProviderResponse u = service.update(a.id(), sameAs("llama", "nouvelle_cle_9876"), ADMIN);
        assertThat(u.apiKeyLast4()).isEqualTo("9876");
        Map<String, Object> payload = auditPayload(a.id(), "ASSISTANT_PROVIDER_UPDATED");
        assertThat(payload).containsEntry("apiKeyChanged", true);
        assertThat(payload.toString()).doesNotContain("nouvelle_cle_9876").doesNotContain("9876");
    }

    @Test
    void modifierLaCleLeModeleOuLAdresseRemetLeFournisseurEnService() {
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        health.recordFailure(a.id(), 401, "HTTP 401");
        assertThat(health.stats(a.id()).cooldownUntil()).isNotNull();
        service.update(a.id(), sameAs("llama", "nouvelle_cle_9876"), ADMIN);
        commit();
        assertThat(health.stats(a.id()).cooldownUntil()).isNull();

        health.recordFailure(a.id(), 401, "HTTP 401");
        service.update(a.id(), sameAs("llama-2", null), ADMIN);
        commit();
        assertThat(health.stats(a.id()).cooldownUntil()).isNull();

        health.recordFailure(a.id(), 401, "HTTP 401");
        service.update(a.id(), new UpdateProviderRequest("Groq", "https://autre-serveur.com/v1", "llama-2",
                "autre_cle_5555", true, 1000, true), ADMIN);
        commit();
        assertThat(health.stats(a.id()).cooldownUntil()).isNull();
    }

    @Test
    void unChangementSansRapportNeTouchePasLaMiseDeCote() {
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        health.recordFailure(a.id(), 429, "quota");
        service.update(a.id(), new UpdateProviderRequest("Groq", "https://api.groq.com/openai/v1", "llama", null,
                true, 500, true), ADMIN);
        commit();
        assertThat(health.stats(a.id()).cooldownUntil()).isNotNull();
    }

    @Test
    void reordonne() {
        AdminProviderResponse a = service.create(create("A"), ADMIN);
        AdminProviderResponse b = service.create(create("B"), ADMIN);
        List<AdminProviderResponse> list = service.reorder(List.of(b.id(), a.id()), ADMIN);
        assertThat(list).extracting(AdminProviderResponse::slug).containsExactly("b", "a");
        assertThat(list).extracting(AdminProviderResponse::priority).containsExactly(1, 2);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("assistant_llm_provider"), eq(null), eq("ASSISTANT_PROVIDER_REORDERED"), eq(ADMIN), payload.capture());
        assertThat(payload.getValue()).containsEntry("order", List.of("b", "a")).hasSize(1);
        assertThat(payload.getValue().toString()).doesNotContain("gsk_secret_1234");
    }

    @Test
    void ordreIncompletOuEnDoubleRefuse() {
        AdminProviderResponse a = service.create(create("A"), ADMIN);
        service.create(create("B"), ADMIN);
        assertThatThrownBy(() -> service.reorder(List.of(a.id()), ADMIN)).isInstanceOf(YadonyBusinessException.class);
        assertThatThrownBy(() -> service.reorder(List.of(a.id(), a.id()), ADMIN)).isInstanceOf(YadonyBusinessException.class);
        assertThatThrownBy(() -> service.reorder(List.of(a.id(), UUID.randomUUID()), ADMIN))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e ->
                        assertThat(violations(e)).containsKey("ids"));
    }

    @Test
    void suppressionDouce() {
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        health.recordFailure(a.id(), 401, "HTTP 401");
        service.delete(a.id(), ADMIN);
        assertThat(service.list()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM assistant_llm_providers WHERE id = ? AND deleted_at IS NOT NULL",
                Integer.class, a.id())).isEqualTo(1);
        commit();
        assertThat(health.stats(a.id()).cooldownUntil()).isNull();
        Map<String, Object> payload = auditPayload(a.id(), "ASSISTANT_PROVIDER_DELETED");
        assertThat(payload).containsEntry("slug", "groq").hasSize(1);
        assertThatThrownBy(() -> service.delete(a.id(), ADMIN)).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }

    @Test
    void chaqueEcritureVideLeCatalogue() {
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        assertThat(catalog.activeProviders()).hasSize(1);
        service.update(a.id(), new UpdateProviderRequest("Groq", "https://api.groq.com/openai/v1", "llama", null, true, null, false), ADMIN);
        commit();
        assertThat(catalog.activeProviders()).isEmpty();
    }

    @Test
    void leCatalogueEtLaSanteNeSontVidesQuApresLeCommit() {
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        assertThat(catalog.activeProviders()).hasSize(1);
        health.recordFailure(a.id(), 401, "HTTP 401");

        AdminProviderResponse u = service.update(a.id(),
                new UpdateProviderRequest("Groq", "https://api.groq.com/openai/v1", "llama", "nouvelle_cle_9876", true, 1000, false), ADMIN);

        // Avant le commit : rien n'est vidé, un appel concurrent verrait encore l'ancien état.
        assertThat(catalog.activeProviders()).hasSize(1);
        assertThat(health.stats(a.id()).cooldownUntil()).isNotNull();
        // La réponse montre déjà la santé remise à zéro.
        assertThat(u.stats().cooldownUntil()).isNull();
        assertThat(u.stats().lastError()).isNull();

        commit();

        assertThat(catalog.activeProviders()).isEmpty();
        assertThat(health.stats(a.id()).cooldownUntil()).isNull();
    }

    @Test
    void ecritureSansTransactionVideTouDeSuite() {
        // Sans transaction active, l'éviction part immédiatement. Le test sort de la transaction de test.
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        commit();
        TestTransaction.end();
        try {
            assertThat(catalog.activeProviders()).hasSize(1);
            service.update(a.id(), new UpdateProviderRequest("Groq", "https://api.groq.com/openai/v1", "llama", null, true, 1000, false), ADMIN);
            assertThat(catalog.activeProviders()).isEmpty();
        } finally {
            TestTransaction.start();
        }
    }

    @Test
    void lesRequetesMasquentLaCleDansToString() {
        CreateProviderRequest c = create("Groq");
        UpdateProviderRequest u = sameAs("llama", "gsk_secret_1234");
        assertThat(c.toString()).doesNotContain("gsk_secret_1234").contains("apiKey=****").contains("name=Groq");
        assertThat(u.toString()).doesNotContain("gsk_secret_1234").contains("apiKey=****").contains("model=llama");
    }

    @Test
    void testRenvoieLeResultatSansLeverDException() {
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        when(client.complete(any(), anyString(), anyString())).thenReturn("{\"answer\":\"ok\"}");
        ProviderTestResponse ok = service.test(a.id());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.sample()).contains("ok");
        when(client.complete(any(), anyString(), anyString())).thenThrow(new LlmCallException(401, "HTTP 401 invalid key"));
        ProviderTestResponse ko = service.test(a.id());
        assertThat(ko.ok()).isFalse();
        assertThat(ko.status()).isEqualTo(401);
        assertThat(ko.error()).contains("401");
    }

    @Test
    void testSansStatutHttpEtEchantillonCoupeSansCasserUnePaireSubrogee() {
        AdminProviderResponse a = service.create(create("Groq"), ADMIN);
        when(client.complete(any(), anyString(), anyString()))
                .thenThrow(new LlmCallException(LlmCallException.NETWORK, "network"));
        ProviderTestResponse ko = service.test(a.id());
        assertThat(ko.ok()).isFalse();
        assertThat(ko.status()).isNull();
        assertThat(ko.sample()).isNull();

        // 199 caractères puis un emoji (paire de substituts aux positions 199 et 200).
        String long199PlusEmoji = "a".repeat(199) + "😀" + "b".repeat(50);
        doReturn(long199PlusEmoji).when(client).complete(any(), anyString(), anyString());
        ProviderTestResponse ok = service.test(a.id());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.sample()).hasSize(199).doesNotContain("\uD83D");
        doReturn("c".repeat(500)).when(client).complete(any(), anyString(), anyString());
        assertThat(service.test(a.id()).sample()).hasSize(200);
    }
}
