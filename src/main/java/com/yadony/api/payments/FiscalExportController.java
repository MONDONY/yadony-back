package com.yadony.api.payments;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Year;
import java.util.List;

@RestController
@RequestMapping("/travelers/me/fiscal-export")
public class FiscalExportController {

    private static final List<String> VALID_FORMATS = List.of("pdf", "csv");
    private static final List<String> VALID_TYPES = List.of("summary", "transactions", "dac7");

    private final FiscalExportService exportService;
    private final UserRepository userRepository;

    public FiscalExportController(FiscalExportService exportService, UserRepository userRepository) {
        this.exportService = exportService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<byte[]> downloadExport(
            @RequestParam int year,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(defaultValue = "transactions") String type) {

        int currentYear = Year.now().getValue();
        if (year < 2020 || year > currentYear) {
            throw YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, "invalid-year", "problem.invalid-year", String.valueOf(currentYear));
        }
        if (!VALID_FORMATS.contains(format)) {
            throw YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, "invalid-format", "problem.invalid-format");
        }
        if (!VALID_TYPES.contains(type)) {
            throw YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, "invalid-type", "problem.invalid-type");
        }

        UserEntity traveler = requireProTraveler();

        byte[] content;
        MediaType mediaType;
        String extension;

        if ("pdf".equals(format)) {
            content = exportService.generateHtml(traveler, year, type);
            mediaType = MediaType.TEXT_HTML;
            extension = "html";
        } else {
            content = exportService.generateCsv(traveler, year, type);
            mediaType = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
            extension = "csv";
        }

        String filename = "yadony-export-" + year + "-" + type + "." + extension;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename).build());
        headers.setContentLength(content.length);

        return ResponseEntity.ok().headers(headers).body(content);
    }

    private UserEntity requireProTraveler() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthenticated", "problem.unauthenticated");
        }
        UserEntity user = userRepository.findByFirebaseUid(auth.getName())
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "pro-required", "problem.pro-required.export-fiscal-reserve-aux-voyageurs-pro");
        }
        return user;
    }
}
