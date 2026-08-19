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
    private final CountryLockService currencyLockService;

    public UserBusinessPrefsService(UserBusinessPrefsRepository repository,
                                    UserRepository userRepository,
                                    CountryLockService currencyLockService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.currencyLockService = currencyLockService;
    }

    @Transactional(readOnly = true)
    public UserBusinessPrefsDto getPrefs(String firebaseUid) {
        UUID userId = resolveUserId(firebaseUid);
        UserBusinessPrefsDto dto = repository.findById(userId)
                .map(this::toDto)
                .orElse(UserBusinessPrefsDto.defaults());
        return withLockStatus(dto, userId);
    }

    @CacheEvict(value = "announcements-search", allEntries = true)
    public UserBusinessPrefsDto upsert(String firebaseUid, UserBusinessPrefsDto dto) {
        UUID userId = resolveUserId(firebaseUid);
        java.util.Optional<UserBusinessPrefsEntity> existing = repository.findById(userId);
        UserBusinessPrefsEntity e = existing.orElseGet(() -> {
            UserBusinessPrefsEntity x = new UserBusinessPrefsEntity();
            x.setUserId(userId);
            return x;
        });
        // Gel au premier mouvement d'argent (lot 2) : changer de devise n'est plus
        // permis une fois un envoi engagé ou un portefeuille entamé, sinon la devise
        // figée d'un bid déjà en cours divergerait silencieusement de ce compte. Un
        // premier choix (aucune ligne persistée) n'a jamais rien à figer.
        if (existing.isPresent()
                && !e.getCurrencyCode().equalsIgnoreCase(dto.currencyCode())
                && currencyLockService.isLocked(userId)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "currency-locked", "Currency Locked",
                    "Impossible de changer de devise : un envoi est en cours ou le "
                            + "portefeuille n'est pas vide.");
        }
        e.setWeightUnit(dto.weightUnit());
        e.setCurrencyCode(dto.currencyCode());
        e.setPickupRadiusKm(dto.pickupRadiusKm());
        e.setDefaultPackageWeightKg(dto.defaultPackageWeightKg());
        e.setMinBidPriceEur(dto.minBidPriceEur());
        e.setContactMode(dto.contactMode());
        e.setResponseDelayHours(dto.responseDelayHours());
        return withLockStatus(toDto(repository.save(e)), userId);
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
                e.getResponseDelayHours(),
                null
        );
    }

    private UserBusinessPrefsDto withLockStatus(UserBusinessPrefsDto dto, UUID userId) {
        return new UserBusinessPrefsDto(
                dto.weightUnit(),
                dto.currencyCode(),
                dto.pickupRadiusKm(),
                dto.defaultPackageWeightKg(),
                dto.minBidPriceEur(),
                dto.contactMode(),
                dto.responseDelayHours(),
                currencyLockService.isLocked(userId)
        );
    }
}
