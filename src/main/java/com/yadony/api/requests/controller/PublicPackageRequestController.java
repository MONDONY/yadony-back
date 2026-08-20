package com.yadony.api.requests.controller;

import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.requests.dto.PublicPackageRequestResponse;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.service.PackageRequestService;
import com.yadony.api.requests.specification.PackageRequestSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/public/package-requests")
public class PublicPackageRequestController {

    private final PackageRequestService service;
    private final PlatformSettingsService settings;

    public PublicPackageRequestController(PackageRequestService service,
                                          PlatformSettingsService settings) {
        this.service = service;
        this.settings = settings;
    }

    @GetMapping
    public Page<PublicPackageRequestResponse> search(
            @RequestParam(required = false) String departure,
            @RequestParam(required = false) String arrival,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) BigDecimal maxWeight,
            @RequestParam(required = false) ParcelSize parcelSize,
            @RequestParam(required = false) Boolean urgent,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Specification<PackageRequestEntity> spec = Specification
                .where(PackageRequestSpecifications.openOnly())
                .and(PackageRequestSpecifications.corridor(departure, arrival))
                .and(PackageRequestSpecifications.dateRange(dateFrom, dateTo))
                .and(PackageRequestSpecifications.maxWeight(maxWeight))
                .and(PackageRequestSpecifications.parcelSize(parcelSize));
        if (Boolean.TRUE.equals(urgent)) {
            spec = spec.and(PackageRequestSpecifications.urgent(settings.urgencyThresholdDays()));
        }
        Pageable pageable = PageRequest.of(page, size);
        return service.searchPublic(spec, pageable);
    }

    @GetMapping("/{id}")
    public PublicPackageRequestResponse getById(@PathVariable UUID id) {
        return service.getPublicById(id);
    }
}
