package com.yadony.api.signalements;

import com.yadony.api.common.YadonyBusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Signalement d'incidents depuis l'app (tout utilisateur authentifié) :
 * upload de captures d'écran puis création du signalement.
 */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/photos")
    public ResponseEntity<Map<String, String>> uploadPhoto(@RequestParam("file") MultipartFile file) {
        String key = reportService.uploadPhoto(requireFirebaseUid(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("key", key));
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create(@Valid @RequestBody CreateReportRequest request) {
        ReportEntity report = reportService.createReport(
                requireFirebaseUid(),
                request.targetType(),
                request.targetId(),
                request.reason(),
                request.description(),
                request.photoKeys());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", report.getId().toString()));
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return (String) auth.getPrincipal();
    }

    public record CreateReportRequest(
            @NotNull ReportTargetType targetType,
            UUID targetId,
            @NotNull ReportReason reason,
            @Size(max = 4000) String description,
            @Size(max = 4) List<String> photoKeys
    ) {}
}
