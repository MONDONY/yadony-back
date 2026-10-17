package com.yadony.api.gppro;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.gppro.dto.GpProApplyRequest;
import com.yadony.api.gppro.dto.GpProAttachDocumentsRequest;
import com.yadony.api.gppro.dto.GpProStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/gp-pro")
public class GpProController {

    private final GpProApplicationService service;

    public GpProController(GpProApplicationService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public GpProStatusResponse me(@AuthenticationPrincipal String firebaseUid) {
        return service.status(requireUid(firebaseUid));
    }

    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public GpProStatusResponse apply(@AuthenticationPrincipal String firebaseUid,
                                     @Valid @RequestBody GpProApplyRequest request) {
        return service.apply(requireUid(firebaseUid), request);
    }

    @PostMapping("/applications/current/documents")
    public GpProStatusResponse attachDocuments(@AuthenticationPrincipal String firebaseUid,
                                               @Valid @RequestBody GpProAttachDocumentsRequest request) {
        return service.attachDocuments(requireUid(firebaseUid), request);
    }

    @DeleteMapping("/applications/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal String firebaseUid) {
        service.cancel(requireUid(firebaseUid));
    }

    private static String requireUid(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return firebaseUid;
    }
}
