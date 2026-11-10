package com.yadony.api.videomodal;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.videomodal.dto.VideoModalActiveRequest;
import com.yadony.api.videomodal.dto.VideoModalAdminResponse;
import com.yadony.api.videomodal.dto.VideoModalRequest;
import com.yadony.api.videomodal.dto.VideoModalVersionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Back-office des modals vidéo. */
@RestController
@RequestMapping("/admin/video-modals")
@PreAuthorize("hasRole('ADMIN')")
public class AdminVideoModalController {

    private final VideoModalAdminService service;

    public AdminVideoModalController(VideoModalAdminService service) {
        this.service = service;
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        service.delete(id, adminId(authentication));
        return ResponseEntity.noContent().build();
    }

    private static UUID adminId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AdminPrincipal p ? p.adminId() : null;
    }
}
