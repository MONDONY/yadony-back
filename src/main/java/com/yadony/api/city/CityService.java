package com.yadony.api.city;

import com.yadony.api.city.dto.CitySearchResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CityService {

    private static final int MAX_LIMIT = 15;
    private final CityRepository cityRepository;

    public CityService(CityRepository cityRepository) {
        this.cityRepository = cityRepository;
    }

    @Cacheable(value = "city-search", key = "#query.trim().toLowerCase() + ':' + T(java.lang.Math).max(1, T(java.lang.Math).min(#limit, 15))")
    public List<CitySearchResponse> search(String query, int limit) {
        if (query == null || query.trim().length() < 2) {
            throw new IllegalArgumentException("query must have at least 2 characters");
        }
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return cityRepository.searchByName(query.trim(), effectiveLimit)
            .stream()
            .map(e -> new CitySearchResponse(
                e.getName(),
                e.getCountryCode(),
                e.getCountryName(),
                e.getLatitude().doubleValue(),
                e.getLongitude().doubleValue()
            ))
            .toList();
    }
}
