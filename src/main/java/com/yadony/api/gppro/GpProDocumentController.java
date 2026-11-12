package com.yadony.api.gppro;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.gppro.dto.GpProDocumentUploadResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/gp-pro/documents")
public class GpProDocumentController {

    private final GpProDocumentService documentService;
    private final GpProApplicationService applicationService;

    public GpProDocumentController(GpProDocumentService documentService,
                                   GpProApplicationService applicationService) {
        this.documentService = documentService;
        this.applicationService = applicationService;
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal String firebaseUid, @PathVariable java.util.UUID documentId) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthorized", "problem.auth.token-required");
        }
        applicationService.removeDocument(firebaseUid, documentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GpProDocumentUploadResponse upload(@AuthenticationPrincipal String firebaseUid,
                                              @RequestParam("file") MultipartFile file,
                                              @RequestParam("type") GpProDocumentType type) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthorized", "problem.auth.token-required");
        }
        GpProDocumentEntity doc = documentService.upload(firebaseUid, file, type);
        return new GpProDocumentUploadResponse(doc.getId(), doc.getDocType());
    }
}
