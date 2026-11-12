package com.yadony.api.address;

import com.yadony.api.address.dto.AutocompleteRequest;
import com.yadony.api.address.dto.AutocompleteSuggestion;
import com.yadony.api.address.dto.PlaceDetailsRequest;
import com.yadony.api.address.dto.PlaceDetailsResponse;
import com.yadony.api.address.dto.ReverseGeocodeRequest;
import com.yadony.api.common.YadonyBusinessException;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
@RestController
@RequestMapping("/addresses")
public class AddressController {

    private final GoogleAddressService service;
    private final GooglePlacesProperties props;
    private final Cache<String, AtomicInteger> rateLimitCache;

    public AddressController(GoogleAddressService service,
                              GooglePlacesProperties props,
                              @Qualifier("addressRateLimitCache")
                              Cache<String, AtomicInteger> rateLimitCache) {
        this.service = service;
        this.props = props;
        this.rateLimitCache = rateLimitCache;
    }

    @PostMapping("/autocomplete")
    public ResponseEntity<List<AutocompleteSuggestion>> autocomplete(
            @Valid @RequestBody AutocompleteRequest request,
            Authentication auth) {
        AtomicInteger counter = checkRateLimit(auth.getName());
        try {
            return ResponseEntity.ok(service.autocomplete(
                request.query(), request.sessionToken(), request.lat(), request.lng()));
        } catch (RuntimeException e) {
            counter.decrementAndGet();
            throw e;
        }
    }

    @PostMapping("/details")
    public ResponseEntity<PlaceDetailsResponse> details(
            @Valid @RequestBody PlaceDetailsRequest request,
            Authentication auth) {
        AtomicInteger counter = checkRateLimit(auth.getName());
        try {
            return ResponseEntity.ok(service.details(request.placeId(), request.sessionToken()));
        } catch (RuntimeException e) {
            counter.decrementAndGet();
            throw e;
        }
    }

    @PostMapping("/reverse")
    public ResponseEntity<PlaceDetailsResponse> reverse(
            @Valid @RequestBody ReverseGeocodeRequest request,
            Authentication auth) {
        AtomicInteger counter = checkRateLimit(auth.getName());
        try {
            return ResponseEntity.ok(
                service.reverse(request.lat(), request.lng())
                    .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "address-not-found", "problem.address-not-found"))
            );
        } catch (RuntimeException e) {
            counter.decrementAndGet();
            throw e;
        }
    }

    private AtomicInteger checkRateLimit(String uid) {
        AtomicInteger counter = rateLimitCache.get(uid, k -> new AtomicInteger(0));
        if (counter.incrementAndGet() > props.rateLimitPerMinute()) {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "rate-limit-exceeded", "problem.rate-limit-exceeded");
        }
        return counter;
    }
}
