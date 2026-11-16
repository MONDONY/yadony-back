package com.yadony.api.assistant.adminhelp;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpArticleResponse;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpAskRequest;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpAskResponse;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpIndexResponse;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpSearchResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;

/** Centre d'aide de la console : ouvert à tout administrateur, support compris. */
@RestController
@RequestMapping("/admin/help")
@PreAuthorize("hasRole('ADMIN')")
public class AdminHelpController {

    private final AdminHelpService service;

    public AdminHelpController(AdminHelpService service) {
        this.service = service;
    }

    @GetMapping("/articles")
    public AdminHelpIndexResponse contents(Authentication authentication) {
        return service.contents(permissions(authentication));
    }

    @GetMapping("/articles/{id}")
    public AdminHelpArticleResponse article(@PathVariable String id, Authentication authentication) {
        return service.article(id, permissions(authentication));
    }

    @GetMapping("/search")
    public AdminHelpSearchResponse search(@RequestParam(name = "q", required = false) String query,
                                          Authentication authentication) {
        return service.search(query, permissions(authentication));
    }

    @PostMapping("/ask")
    public AdminHelpAskResponse ask(@Valid @RequestBody AdminHelpAskRequest request, Authentication authentication) {
        return service.ask(AdminPrincipal.requireAdminId(authentication), permissions(authentication), request);
    }

    /** Permissions effectives de l'admin (rôle et dérogations), telles que posées par le filtre d'authentification. */
    static Set<String> permissions(Authentication authentication) {
        if (authentication == null) return Set.of();
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }
}
