package com.yadony.api.assistant.provider;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.assistant.provider.dto.AdminProviderResponse;
import com.yadony.api.assistant.provider.dto.CreateProviderRequest;
import com.yadony.api.assistant.provider.dto.ProviderTestResponse;
import com.yadony.api.assistant.provider.dto.ReorderProvidersRequest;
import com.yadony.api.assistant.provider.dto.UpdateProviderRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/assistant/providers")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('ASSISTANT_MANAGE')")
public class AdminAssistantProviderController {

    private final AssistantProviderAdminService service;

    public AdminAssistantProviderController(AssistantProviderAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminProviderResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminProviderResponse create(@Valid @RequestBody CreateProviderRequest request, Authentication authentication) {
        return service.create(request, AdminPrincipal.requireAdminId(authentication));
    }

    @PatchMapping("/{id}")
    public AdminProviderResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProviderRequest request,
                                        Authentication authentication) {
        return service.update(id, request, AdminPrincipal.requireAdminId(authentication));
    }

    @PutMapping("/order")
    public List<AdminProviderResponse> reorder(@Valid @RequestBody ReorderProvidersRequest request,
                                               Authentication authentication) {
        return service.reorder(request.ids(), AdminPrincipal.requireAdminId(authentication));
    }

    @PostMapping("/{id}/test")
    public ProviderTestResponse test(@PathVariable UUID id) {
        return service.test(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        service.delete(id, AdminPrincipal.requireAdminId(authentication));
    }
}
