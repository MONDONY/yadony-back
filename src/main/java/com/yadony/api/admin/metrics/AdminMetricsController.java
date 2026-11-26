package com.yadony.api.admin.metrics;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/metrics")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMetricsController {

    private final AdminMetricsService metricsService;

    public AdminMetricsController(AdminMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('METRICS_VIEW')")
    public ResponseEntity<AdminOverviewResponse> getOverview(
            @RequestParam(required = false) AdminMetricsService.Period period) {
        // Valeur inconnue : MethodArgumentTypeMismatchException -> 400 ProblemDetail (GlobalExceptionHandler).
        return ResponseEntity.ok(metricsService.buildOverview(period));
    }
}
