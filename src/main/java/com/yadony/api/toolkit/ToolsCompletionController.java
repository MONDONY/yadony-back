package com.yadony.api.toolkit;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Complétion des outils (adresses, destinataires, alertes, modèles, grille de
 * prix) : alimente la carte « Publiez en 3 taps » de l'onglet Activités.
 * Lecture seule, sans cache : cinq COUNT indexés, et un TTL afficherait un
 * badge périmé au retour d'un outil que l'on vient de remplir.
 */
@RestController
@RequestMapping("/users")
public class ToolsCompletionController {

    private final ToolsCompletionService service;
    private final UserRepository userRepository;

    public ToolsCompletionController(ToolsCompletionService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping("/me/tools-completion")
    public ResponseEntity<ToolsCompletionResponse> getMyToolsCompletion() {
        UserEntity user = userRepository.findByFirebaseUid(requireFirebaseUid())
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        return ResponseEntity.ok(service.compute(user.getId()));
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthenticated", "problem.unauthenticated");
        }
        return auth.getName();
    }
}
