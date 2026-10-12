package com.yadony.api.flights;

import com.yadony.api.flights.dto.FlightAlertRequest;
import com.yadony.api.flights.dto.FlightAlertResponse;
import com.yadony.api.flights.dto.FlightAlertUpdateRequest;
import com.yadony.api.flights.dto.FlightClickRequest;
import com.yadony.api.flights.dto.FlightClickResponse;
import com.yadony.api.flights.dto.FlightDealsResponse;
import com.yadony.api.flights.dto.FlightDefaultsDto;
import com.yadony.api.flights.dto.FlightPlaceDto;
import com.yadony.api.flights.dto.FlightSearchResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Billets d'avion en affiliation (Travelpayouts / Aviasales) : recherche, bons plans,
 * alertes prix et lien affilié.
 *
 * <p>Pas de {@code @PreAuthorize} par rôle : ces routes ne demandent aucun rôle métier.
 * Elles sont absentes de la liste blanche invités de {@code SecurityConfig}, donc
 * réservées aux comptes inscrits (authentifiés et non invités) par la règle finale.
 */
@RestController
public class FlightController {

    private final FlightSearchService searchService;
    private final FlightPlaceService placeService;
    private final FlightDealService dealService;
    private final FlightAlertService alertService;
    private final FlightClickService clickService;
    private final FlightDefaultsService defaultsService;
    private final FlightUsers users;

    public FlightController(FlightSearchService searchService, FlightPlaceService placeService,
                            FlightDealService dealService, FlightAlertService alertService,
                            FlightClickService clickService, FlightDefaultsService defaultsService, FlightUsers users) {
        this.searchService = searchService;
        this.placeService = placeService;
        this.dealService = dealService;
        this.alertService = alertService;
        this.clickService = clickService;
        this.defaultsService = defaultsService;
        this.users = users;
    }

    @GetMapping("/flights/search")
    public FlightSearchResponse search(@AuthenticationPrincipal String firebaseUid,
                                       @RequestParam String origin,
                                       @RequestParam String destination,
                                       @RequestParam(required = false) String departureDate,
                                       @RequestParam(required = false) String returnDate,
                                       @RequestParam(defaultValue = "false") boolean direct,
                                       @RequestParam(required = false) String currency) {
        return searchService.search(firebaseUid, origin, destination, departureDate, returnDate, direct, currency);
    }

    @GetMapping("/flights/places")
    public List<FlightPlaceDto> places(@AuthenticationPrincipal String firebaseUid,
                                       @RequestParam String term) {
        return placeService.suggest(term, users.resolve(firebaseUid).language());
    }

    @GetMapping("/flights/deals")
    public FlightDealsResponse deals(@AuthenticationPrincipal String firebaseUid,
                                     @RequestParam(required = false) String currency) {
        return dealService.deals(firebaseUid, currency);
    }

    @GetMapping("/flights/defaults")
    public FlightDefaultsDto defaults(@AuthenticationPrincipal String firebaseUid) {
        return defaultsService.defaults(firebaseUid);
    }

    @PostMapping("/flights/clicks")
    public FlightClickResponse click(@AuthenticationPrincipal String firebaseUid,
                                     @Valid @RequestBody FlightClickRequest request) {
        return clickService.click(firebaseUid, request);
    }

    @GetMapping("/me/flight-alerts")
    public List<FlightAlertResponse> listAlerts(@AuthenticationPrincipal String firebaseUid) {
        return alertService.list(firebaseUid);
    }

    @PostMapping("/me/flight-alerts")
    @ResponseStatus(HttpStatus.CREATED)
    public FlightAlertResponse createAlert(@AuthenticationPrincipal String firebaseUid,
                                           @Valid @RequestBody FlightAlertRequest request) {
        return alertService.create(firebaseUid, request);
    }

    @PatchMapping("/me/flight-alerts/{id}")
    public FlightAlertResponse updateAlert(@AuthenticationPrincipal String firebaseUid,
                                           @PathVariable UUID id,
                                           @Valid @RequestBody FlightAlertUpdateRequest request) {
        return alertService.update(firebaseUid, id, request);
    }

    @DeleteMapping("/me/flight-alerts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAlert(@AuthenticationPrincipal String firebaseUid, @PathVariable UUID id) {
        alertService.delete(firebaseUid, id);
    }
}
