package com.yadony.api.videomodal;

import com.yadony.api.videomodal.dto.VideoModalEligibleResponse;
import com.yadony.api.videomodal.dto.VideoModalEventRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Modals vidéo côté app.
 *
 * <p>{@code GET /video-modals/eligible} est public (permitAll dans SecurityConfig) : le filtre
 * Firebase renseigne tout de même l'appelant quand un jeton valide est présent, et la liste est
 * alors filtrée pour lui. {@code caps=layout} (nouvelle app) autorise l'envoi des formats
 * autres que la feuille du bas et des images ; sans lui ces campagnes sont retenues. Un invité (session anonyme) est traité comme un visiteur.
 * {@code POST /video-modals/{id}/events} retombe sur la règle finale : vrai compte exigé.
 */
@RestController
@RequestMapping("/video-modals")
public class VideoModalController {

    private final VideoModalService service;

    public VideoModalController(VideoModalService service) {
        this.service = service;
    }

    @GetMapping("/eligible")
    public List<VideoModalEligibleResponse> eligible(@RequestParam(name = "lang", required = false) String lang,
                                                     @RequestParam(name = "caps", required = false) String caps,
                                                     Authentication authentication) {
        return service.eligible(accountUid(authentication), lang, caps);
    }

    @PostMapping("/{id}/events")
    public ResponseEntity<Void> event(@PathVariable UUID id, @Valid @RequestBody VideoModalEventRequest request,
                                      Authentication authentication) {
        service.recordEvent(accountUid(authentication), id, request);
        return ResponseEntity.noContent().build();
    }

    /** uid Firebase d'un vrai compte, {@code null} pour un visiteur ou un invité. */
    static String accountUid(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof String uid)) {
            return null;
        }
        boolean guest = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_GUEST".equals(a.getAuthority()));
        return guest ? null : uid;
    }
}
