package com.yadony.api.assistant.formdraft;

import com.yadony.api.assistant.AssistantProperties;
import com.yadony.api.assistant.formdraft.dto.FormDraftRequest;
import com.yadony.api.assistant.formdraft.dto.FormDraftResponse;
import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Formulaires remplis par l'IA. Même règle d'accès que /assistant/ask : authentifié et non invité. */
@RestController
@RequestMapping("/assistant/form-draft")
public class FormDraftController {

    private final FormDraftEngine engine;
    private final AssistantProperties assistant;
    private final FormDraftProperties properties;
    private final TripDraftSchema trip;
    private final PackageRequestDraftSchema packageRequest;

    public FormDraftController(FormDraftEngine engine, AssistantProperties assistant, FormDraftProperties properties,
                               TripDraftSchema trip, PackageRequestDraftSchema packageRequest) {
        this.engine = engine;
        this.assistant = assistant;
        this.properties = properties;
        this.trip = trip;
        this.packageRequest = packageRequest;
    }

    @PostMapping("/trip")
    public FormDraftResponse trip(@AuthenticationPrincipal String firebaseUid,
                                  @Valid @RequestBody FormDraftRequest request) {
        return run(trip, firebaseUid, request);
    }

    @PostMapping("/package-request")
    public FormDraftResponse packageRequest(@AuthenticationPrincipal String firebaseUid,
                                            @Valid @RequestBody FormDraftRequest request) {
        return run(packageRequest, firebaseUid, request);
    }

    private FormDraftResponse run(FormDraftSchema schema, String firebaseUid, FormDraftRequest request) {
        if (!assistant.enabled() || !properties.enabled()) {
            throw new YadonyBusinessException(HttpStatus.NOT_FOUND, "assistant-disabled", "Not Found",
                    "L'assistant n'est pas disponible.");
        }
        // Un principal admin n'est pas un String : sans uid Firebase, le quota ne pourrait pas être tenu.
        if (firebaseUid == null || firebaseUid.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "assistant-user-required", "Forbidden",
                    "L'assistant est réservé aux comptes utilisateur.");
        }
        return engine.run(schema, firebaseUid, request);
    }
}
