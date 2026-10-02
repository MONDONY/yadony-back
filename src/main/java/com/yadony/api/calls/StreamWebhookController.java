package com.yadony.api.calls;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Webhook Stream Video. Public, authentifié par signature HMAC du corps brut. */
@RestController
public class StreamWebhookController {

    private final StreamWebhookVerifier verifier;
    private final CallWebhookService service;
    private final ObjectMapper objectMapper;

    public StreamWebhookController(StreamWebhookVerifier verifier, CallWebhookService service, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/calls/webhook")
    public ResponseEntity<Void> receive(@RequestBody byte[] body,
                                        @RequestHeader(value = "X-Signature", required = false) String signature,
                                        @RequestHeader(value = "X-Api-Key", required = false) String apiKey) throws IOException {
        if (!verifier.verify(body, signature, apiKey)) return ResponseEntity.status(401).build();
        service.handle(objectMapper.readTree(body));
        return ResponseEntity.ok().build();
    }
}
