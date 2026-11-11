package com.yadony.api.city;

import com.yadony.api.city.dto.CitySearchResponse;
import com.yadony.api.city.dto.PopularCorridorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
@RestController
@RequestMapping("/cities")
public class CityController {

    private final CityService cityService;
    private final CorridorService corridorService;

    public CityController(CityService cityService, CorridorService corridorService) {
        this.cityService = cityService;
        this.corridorService = corridorService;
    }

    /**
     * Ouverte aussi aux invités (session Firebase anonyme) : un visiteur sans compte doit avoir des
     * suggestions de villes dans le filtre des trajets. Lecture seule d'un référentiel public ; sans
     * jeton, la requête reste refusée (401).
     */
    @PreAuthorize("hasAnyRole('GUEST', 'SENDER', 'TRAVELER')")
    @GetMapping("/search")
    public ResponseEntity<List<CitySearchResponse>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.unprocessableEntity().build();
        }
        return ResponseEntity.ok(cityService.search(q, limit));
    }

    @GetMapping("/corridors/popular")
    public ResponseEntity<List<PopularCorridorResponse>> popularCorridors(
            @RequestParam(defaultValue = "6") int limit) {
        return ResponseEntity.ok(corridorService.getPopular(limit));
    }
}
