package com.yadony.api.videomodal;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.videomodal.dto.AudienceEstimateRequest;
import com.yadony.api.videomodal.dto.AudienceEstimateResponse;
import com.yadony.api.videomodal.dto.VideoModalActiveRequest;
import com.yadony.api.videomodal.dto.VideoModalAdminResponse;
import com.yadony.api.videomodal.dto.VideoModalRequest;
import com.yadony.api.videomodal.dto.VideoModalVersionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Back-office des modals. Réservé à NOTIFICATION_SEND, comme les diffusions : SUPPORT ne la reçoit
 * pas. {@code hasRole('ADMIN')} seul ne discrimine personne (tout compte admin le porte, SUPPORT
 * compris) : seule l'authority filtre réellement.
 */
@RestController
@RequestMapping("/admin/video-modals")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('NOTIFICATION_SEND')")
public class AdminVideoModalController {

    private final VideoModalAdminService service;
    private final VideoModalImageService imageService;

    public AdminVideoModalController(VideoModalAdminService service, VideoModalImageService imageService) {
        this.service = service;
        this.imageService = imageService;
    }

    @GetMapping
    public List<VideoModalAdminResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public VideoModalAdminResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<VideoModalAdminResponse> create(@Valid @RequestBody VideoModalRequest request,
                                                          Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, adminId(authentication)));
    }

    /**
     * Estimation d'audience du ciblage en cours de saisie (moteur de la Diffusion) : comptes concernés
     * puis après tirage. Lecture seule, jamais de donnée personnelle.
     */
    @PostMapping("/audience-estimate")
    public AudienceEstimateResponse audienceEstimate(@RequestBody AudienceEstimateRequest request) {
        return service.estimateAudience(request);
    }

    @PutMapping("/{id}")
    public VideoModalAdminResponse update(@PathVariable UUID id, @Valid @RequestBody VideoModalRequest request,
                                          Authentication authentication) {
        return service.update(id, request, adminId(authentication));
    }

    @PostMapping("/{id}/versions")
    public VideoModalAdminResponse publishVersion(@PathVariable UUID id,
                                                  @Valid @RequestBody VideoModalVersionRequest request,
                                                  Authentication authentication) {
        return service.publishVersion(id, request, adminId(authentication));
    }

    @PatchMapping("/{id}/active")
    public VideoModalAdminResponse setActive(@PathVariable UUID id, @Valid @RequestBody VideoModalActiveRequest request,
                                             Authentication authentication) {
        return service.setActive(id, request.active(), adminId(authentication));
    }

    /** Remplace l'image de la campagne (champ multipart {@code file}) : JPEG, PNG ou WebP, 3 Mo, 2400 px. */
    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VideoModalAdminResponse uploadImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
                                               Authentication authentication) {
        return imageService.upload(id, file, adminId(authentication));
    }

    /** Retire l'image (le mode d'image repasse à NONE). */
    @DeleteMapping("/{id}/image")
    public VideoModalAdminResponse removeImage(@PathVariable UUID id, Authentication authentication) {
        return imageService.remove(id, adminId(authentication));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        service.delete(id, adminId(authentication));
        return ResponseEntity.noContent().build();
    }

    private static UUID adminId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AdminPrincipal p ? p.adminId() : null;
    }
}
