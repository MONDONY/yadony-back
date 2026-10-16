package com.yadony.api.assistant;

import com.yadony.api.assistant.dto.AssistantAskRequest;
import com.yadony.api.assistant.dto.AssistantAskResponse;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/assistant")
public class AssistantController {

    private final AssistantService service;
    private final AssistantProperties properties;

    public AssistantController(AssistantService service, AssistantProperties properties) {
        this.service = service;
        this.properties = properties;
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
