package com.yadony.api.travelerstatus;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.travelerstatus.dto.AdminIndividualFrequencyFlagRow;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * File « Particuliers à confirmer » (lot 4), mêmes droits que la revue GP Pro. Chaque méthode re-déclare
 * {@code hasRole('ADMIN')} : une {@code @PreAuthorize} de méthode remplace celle de la classe.
 */
@RestController
@RequestMapping("/admin/individual-frequency-flags")
@PreAuthorize("hasRole('ADMIN')")
public class AdminIndividualFrequencyController {

    private final IndividualFrequencyAdminService service;

    public AdminIndividualFrequencyController(IndividualFrequencyAdminService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('GP_PRO_REVIEW')")
    @GetMapping
    public List<AdminIndividualFrequencyFlagRow> list() {
        return service.list();
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('GP_PRO_REVIEW')")
    @PostMapping("/{flagId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable UUID flagId, Authentication authentication) {
        service.dismiss(flagId, AdminPrincipal.requireAdminId(authentication));
    }
}
