package com.yadony.api.settings;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Un utilisateur est figé dans son pays dès qu'un compte Stripe Connect existe pour
 * lui : le pays d'un compte Connect est immuable chez Stripe, une fois le compte créé,
 * changer de pays côté yadony produirait une divergence irrattrapable. Aucune colonne
 * dédiée : l'état se dérive, pour ne pas dupliquer une source de vérité.
 */
@Component
public class CountryLockService {

    private final UserRepository userRepository;

    public CountryLockService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean isLocked(UUID userId) {
        return hasStripeAccount(userId);
    }

    private boolean hasStripeAccount(UUID userId) {
        return userRepository.findById(userId)
                .map(UserEntity::getStripeAccountId)
                .filter(id -> !id.isBlank())
                .isPresent();
    }
}
