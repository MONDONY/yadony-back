package com.yadony.api.assistant.provider;

import com.yadony.api.assistant.llm.LlmCallException;
import com.yadony.api.assistant.llm.LlmProviderConfig;
import com.yadony.api.assistant.llm.OpenAiCompatibleClient;
import com.yadony.api.assistant.llm.ProviderHealthRegistry;
import com.yadony.api.assistant.provider.dto.AdminProviderResponse;
import com.yadony.api.assistant.provider.dto.AdminProviderStats;
import com.yadony.api.assistant.provider.dto.CreateProviderRequest;
import com.yadony.api.assistant.provider.dto.ProviderTestResponse;
import com.yadony.api.assistant.provider.dto.UpdateProviderRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Gestion des fournisseurs d'IA depuis l'admin. La clé d'API n'est jamais renvoyée (seulement ses 4
 * derniers caractères) ni écrite dans l'audit, pas même tronquée.
 */
@Service
public class AssistantProviderAdminService {

    static final String ENTITY = "assistant_llm_provider";
    private static final String TEST_SYSTEM = "Reply with the JSON object {\"answer\": \"ok\", \"answered\": true, \"routes\": []} and nothing else.";
    private static final int SAMPLE_MAX = 200;

    private final AssistantLlmProviderRepository repository;
    private final ProviderCatalog catalog;
    private final ProviderHealthRegistry health;
    private final OpenAiCompatibleClient client;
    private final AuditService auditService;

    public AssistantProviderAdminService(AssistantLlmProviderRepository repository, ProviderCatalog catalog,
                                         ProviderHealthRegistry health, OpenAiCompatibleClient client,
                                         AuditService auditService) {
        this.repository = repository;
        this.catalog = catalog;
        this.health = health;
        this.client = client;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<AdminProviderResponse> list() {
        return repository.findAllByOrderByPriorityAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public AdminProviderResponse create(CreateProviderRequest request, UUID adminId) {
        AssistantLlmProviderEntity e = new AssistantLlmProviderEntity();
        e.setName(request.name().strip());
        e.setSlug(SlugMaker.unique(request.name(), repository::existsBySlug));
        e.setBaseUrl(validBaseUrl(request.baseUrl()));
        e.setModel(request.model().strip());
        e.setApiKey(request.apiKey().strip());
        e.setSupportsJsonMode(request.supportsJsonMode());
        e.setDailyRequestLimit(request.dailyRequestLimit());
        e.setEnabled(request.enabled());
        e.setPriority(repository.findAllByOrderByPriorityAsc().stream()
                .mapToInt(AssistantLlmProviderEntity::getPriority).max().orElse(0) + 1);
        AssistantLlmProviderEntity saved = repository.save(e);
        Map<String, Object> payload = new HashMap<>();
        payload.put("slug", saved.getSlug());
        payload.put("model", saved.getModel());
        payload.put("supportsJsonMode", saved.isSupportsJsonMode());
        payload.put("dailyRequestLimit", saved.getDailyRequestLimit());
        payload.put("enabled", saved.isEnabled());
        auditService.log(ENTITY, saved.getId(), "ASSISTANT_PROVIDER_CREATED", adminId, payload);
        afterCommit(catalog::evict);
        return toResponse(saved);
    }

    @Transactional
    public AdminProviderResponse update(UUID id, UpdateProviderRequest request, UUID adminId) {
        AssistantLlmProviderEntity e = require(id);
        // Contrôles AVANT toute modification : l'entité est gérée, un setter suivi d'une exception
        // laisserait l'objet muté en mémoire.
        String name = request.name().strip();
        String baseUrl = validBaseUrl(request.baseUrl());
        String model = request.model().strip();
        boolean keyChanged = request.apiKey() != null && !request.apiKey().isBlank();
        boolean baseUrlChanged = !e.getBaseUrl().equals(baseUrl);
        if (baseUrlChanged && !keyChanged) {
            // Sans cette règle, un compte admin compromis pourrait pointer l'adresse vers son propre
            // serveur et recevoir la clé existante au prochain appel.
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "validation", "Validation Error",
                    "Saisissez à nouveau la clé quand l'adresse change.",
                    Map.of("violations", Map.of("apiKey", "Saisissez à nouveau la clé quand l'adresse change.")));
        }
        boolean modelChanged = !e.getModel().equals(model);

        List<String> changed = new ArrayList<>();
        if (!e.getName().equals(name)) { changed.add("label"); e.setName(name); }
        if (baseUrlChanged) { changed.add("baseUrl"); e.setBaseUrl(baseUrl); }
        if (modelChanged) { changed.add("model"); e.setModel(model); }
        if (e.isSupportsJsonMode() != request.supportsJsonMode()) { changed.add("supportsJsonMode"); e.setSupportsJsonMode(request.supportsJsonMode()); }
        if (!Objects.equals(e.getDailyRequestLimit(), request.dailyRequestLimit())) { changed.add("dailyRequestLimit"); e.setDailyRequestLimit(request.dailyRequestLimit()); }
        if (e.isEnabled() != request.enabled()) { changed.add("enabled"); e.setEnabled(request.enabled()); }
        if (keyChanged) e.setApiKey(request.apiKey().strip());

        Map<String, Object> payload = new HashMap<>();
        payload.put("slug", e.getSlug());
        payload.put("changed", changed);
        payload.put("apiKeyChanged", keyChanged);
        auditService.log(ENTITY, e.getId(), "ASSISTANT_PROVIDER_UPDATED", adminId, payload);
        boolean resetHealth = keyChanged || baseUrlChanged || modelChanged;
        UUID providerId = e.getId();
        if (resetHealth) afterCommit(() -> health.reset(providerId));
        afterCommit(catalog::evict);
        return toResponse(e, resetHealth);
    }

    @Transactional
    public List<AdminProviderResponse> reorder(List<UUID> ids, UUID adminId) {
        List<AssistantLlmProviderEntity> all = repository.findAllByOrderByPriorityAsc();
        Map<UUID, AssistantLlmProviderEntity> byId = new HashMap<>();
        all.forEach(p -> byId.put(p.getId(), p));
        if (ids.size() != all.size() || new HashSet<>(ids).size() != ids.size() || !byId.keySet().containsAll(ids)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "validation", "Validation Error",
                    "L'ordre doit contenir chaque fournisseur une seule fois.",
                    Map.of("violations", Map.of("ids", "L'ordre doit contenir chaque fournisseur une seule fois.")));
        }
        List<String> order = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            AssistantLlmProviderEntity p = byId.get(ids.get(i));
            p.setPriority(i + 1);
            order.add(p.getSlug());
        }
        auditService.log(ENTITY, null, "ASSISTANT_PROVIDER_REORDERED", adminId, Map.of("order", order));
        afterCommit(catalog::evict);
        return ids.stream().map(byId::get).map(this::toResponse).toList();
    }

    @Transactional
    public void delete(UUID id, UUID adminId) {
        AssistantLlmProviderEntity e = require(id);
        e.softDelete();
        auditService.log(ENTITY, e.getId(), "ASSISTANT_PROVIDER_DELETED", adminId, Map.of("slug", e.getSlug()));
        UUID providerId = e.getId();
        afterCommit(() -> health.reset(providerId));
        afterCommit(catalog::evict);
    }

    /**
     * Volontairement sans transaction : la lecture du dépôt a la sienne, courte, et la connexion à la base
     * n'est pas gardée pendant l'appel externe (jusqu'à 20 s).
     */
    public ProviderTestResponse test(UUID id) {
        AssistantLlmProviderEntity e = require(id);
        LlmProviderConfig config = ProviderCatalog.toConfig(e);
        long start = System.nanoTime();
        try {
            String content = client.complete(config, TEST_SYSTEM, "Test Yadony");
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new ProviderTestResponse(true, 200, ms, cap(content), null);
        } catch (LlmCallException ex) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            Integer status = ex.status() > 0 ? ex.status() : null;
            // Le message d'une LlmCallException est un texte constant : jamais le corps du fournisseur.
            return new ProviderTestResponse(false, status, ms, null, ex.getMessage());
        }
    }

    /** Coupe à 200 caractères sans scinder une paire de substituts (emoji). */
    private static String cap(String s) {
        if (s == null || s.length() <= SAMPLE_MAX) return s;
        int end = Character.isHighSurrogate(s.charAt(SAMPLE_MAX - 1)) ? SAMPLE_MAX - 1 : SAMPLE_MAX;
        return s.substring(0, end);
    }

    private AssistantLlmProviderEntity require(UUID id) {
        // Le filtre sur deletedAt couvre le cas d'une entité supprimée dans la même transaction, que
        // findById renvoie depuis le contexte de persistance sans repasser par @Where.
        return repository.findById(id).filter(p -> p.getDeletedAt() == null).orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "assistant-provider-not-found", "Not Found", "Fournisseur introuvable."));
    }

    private static String validBaseUrl(String raw) {
        try {
            return BaseUrlValidator.normalize(raw);
        } catch (IllegalArgumentException e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "validation", "Validation Error",
                    e.getMessage(), Map.of("violations", Map.of("baseUrl", e.getMessage())));
        }
    }

    /**
     * Le cache du catalogue et la santé du fournisseur ne sont vidés qu'après le commit : vidés avant,
     * un appel concurrent pourrait les repeupler avec l'ancien état (fournisseur supprimé ou désactivé
     * encore utilisé jusqu'à 60 s). Sans transaction active, l'action part tout de suite.
     */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private AdminProviderResponse toResponse(AssistantLlmProviderEntity e) {
        return toResponse(e, false);
    }

    /** {@code healthCleared} : la remise à zéro de la santé est différée après le commit, la réponse la montre déjà. */
    private AdminProviderResponse toResponse(AssistantLlmProviderEntity e, boolean healthCleared) {
        ProviderHealthRegistry.ProviderStats s = health.stats(e.getId());
        if (healthCleared) {
            s = new ProviderHealthRegistry.ProviderStats(s.requestsToday(), s.successesToday(), null, null, null);
        }
        return new AdminProviderResponse(e.getId(), e.getName(), e.getSlug(), e.getBaseUrl(), e.getModel(),
                e.getApiKeyLast4(), e.isSupportsJsonMode(), e.getDailyRequestLimit(), e.getPriority(), e.isEnabled(),
                new AdminProviderStats(s.requestsToday(), s.successesToday(), s.lastStatus(), s.lastError(), s.cooldownUntil()));
    }
}
