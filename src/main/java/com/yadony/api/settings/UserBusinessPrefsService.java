package com.yadony.api.settings;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class UserBusinessPrefsService {

    private final UserBusinessPrefsRepository repository;
    private final UserRepository userRepository;

    public UserBusinessPrefsService(UserBusinessPrefsRepository repository,
                                    UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserBusinessPrefsDto getPrefs(String firebaseUid) {
        UUID userId = resolveUserId(firebaseUid);
        return repository.findById(userId).map(this::toDto).orElse(UserBusinessPrefsDto.defaults());
    }

    @CacheEvict(value = "announcements-search", allEntries = true)
    public UserBusinessPrefsDto upsert(String firebaseUid, UserBusinessPrefsDto dto) {
        UUID userId = resolveUserId(firebaseUid);
        UserBusinessPrefsEntity e = repository.findById(userId).orElseGet(() -> {
            UserBusinessPrefsEntity x = new UserBusinessPrefsEntity();
            x.setUserId(userId);
            return x;
        });
        e.setWeightUnit(dto.weightUnit());
        e.setCurrencyCode(dto.currencyCode());
        e.setPickupRadiusKm(dto.pickupRadiusKm());
        e.setDefaultPackageWeightKg(dto.defaultPackageWeightKg());
        e.setMinBidPriceEur(dto.minBidPriceEur());
        e.setContactMode(dto.contactMode());
        e.setResponseDelayHours(dto.responseDelayHours());
        return toDto(repository.save(e));
    }

    private UUID resolveUserId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(u -> u.getId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user_not_found",
                        "User not found", "Utilisateur introuvable"));
    }

    private UserBusinessPrefsDto toDto(UserBusinessPrefsEntity e) {
        return new UserBusinessPrefsDto(
                e.getWeightUnit(),
                e.getCurrencyCode(),
                e.getPickupRadiusKm(),
                e.getDefaultPackageWeightKg(),
                e.getMinBidPriceEur(),
                e.getContactMode(),
                e.getResponseDelayHours()
        );
    }
}
