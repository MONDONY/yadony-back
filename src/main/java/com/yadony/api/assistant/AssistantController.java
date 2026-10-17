package com.yadony.api.assistant;

import com.yadony.api.assistant.dto.AssistantAskRequest;
import com.yadony.api.assistant.dto.AssistantAskResponse;
import com.yadony.api.assistant.dto.AssistantScreenResponse;
import com.yadony.api.assistant.dto.AssistantStatusResponse;
import com.yadony.api.assistant.formdraft.FormDraftProperties;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/assistant")
public class AssistantController {

    private final AssistantService service;
    private final AssistantProperties properties;
    private final FormDraftProperties formDraft;

    public AssistantController(AssistantService service, AssistantProperties properties, FormDraftProperties formDraft) {
        this.service = service;
        this.properties = properties;
        this.formDraft = formDraft;
    }

    /**
     * Interrupteur lu par l'app au démarrage pour n'afficher la bulle que si l'assistant est allumé.
     * Répond toujours 200 (jamais 404 quand il est éteint : l'app lit un 404 comme « back ancien »).
     * Même règle d'accès que {@code /ask} (anyRequest : authentifié et non invité), sans fournisseur,
     * sans quota, sans écriture.
     */
    @GetMapping("/status")
    public AssistantStatusResponse status() {
        return new AssistantStatusResponse(properties.enabled(), properties.enabled() && formDraft.enabled());
    }

    /**
     * Explication de l'écran {@code path} (motif de route GoRouter, ex. {@code /bids/:bidId}) : 200 avec la fiche,
     * 204 si aucun écran ne correspond. Même accès que {@code /ask}, sans quota ni fournisseur.
     */
    @GetMapping("/screen")
    public ResponseEntity<AssistantScreenResponse> screen(
            @RequestParam String path, @RequestParam(required = false) String locale) {
        if (!properties.enabled()) {
            throw new YadonyBusinessException(HttpStatus.NOT_FOUND, "assistant-disabled", "Not Found",
                    "L'assistant n'est pas disponible.");
        }
        return service.screen(path, locale).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/ask")
    public AssistantAskResponse ask(@AuthenticationPrincipal String firebaseUid,
                                    @Valid @RequestBody AssistantAskRequest request) {
        if (!properties.enabled()) {
            throw new YadonyBusinessException(HttpStatus.NOT_FOUND, "assistant-disabled", "Not Found",
                    "L'assistant n'est pas disponible.");
        }
        // Un principal admin n'est pas un String : sans uid Firebase, le quota ne pourrait pas être tenu.
        if (firebaseUid == null || firebaseUid.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "assistant-user-required", "Forbidden",
                    "L'assistant est réservé aux comptes utilisateur.");
        }
        return service.ask(firebaseUid, request);
    }
}
