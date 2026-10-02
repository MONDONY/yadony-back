package com.yadony.api.calls;

import com.yadony.api.calls.dto.CallTokenResponse;
import com.yadony.api.calls.dto.StartCallResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@PreAuthorize("isAuthenticated()")
public class CallController {

    private final CallService callService;

    public CallController(CallService callService) {
        this.callService = callService;
    }

    @GetMapping("/calls/token")
    public CallTokenResponse token(@AuthenticationPrincipal String firebaseUid) {
        return callService.token(firebaseUid);
    }

    @PostMapping("/conversations/{conversationId}/calls")
    @ResponseStatus(HttpStatus.CREATED)
    public StartCallResponse start(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID conversationId) {
        return callService.start(firebaseUid, conversationId);
    }
}
