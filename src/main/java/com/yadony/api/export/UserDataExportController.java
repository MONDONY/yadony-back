package com.yadony.api.export;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.export.dto.UserDataExportDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** RGPD art. 15 (droit d'accès) — export JSON des données de l'utilisateur connecté. */
@RestController
@RequestMapping("/users/me")
public class UserDataExportController {

    private final UserDataExportService exportService;
    private final UserRepository userRepository;

    public UserDataExportController(UserDataExportService exportService, UserRepository userRepository) {
        this.exportService = exportService;
        this.userRepository = userRepository;
    }

    @GetMapping("/export")
    public ResponseEntity<UserDataExportDto> export() {
        UserEntity user = requireCurrentUser();
        return ResponseEntity.ok(exportService.export(user));
    }

    private UserEntity requireCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthenticated", "problem.unauthenticated");
        }
        return userRepository.findByFirebaseUid(auth.getName())
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
    }
}
