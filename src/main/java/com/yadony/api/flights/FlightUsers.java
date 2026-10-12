package com.yadony.api.flights;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Identité de l'appelant pour le package vols : identifiant et langue d'affichage. */
@Component
public class FlightUsers {

    public record Caller(UUID id, String language) {
    }

    private final UserRepository userRepository;

    public FlightUsers(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Caller resolve(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyNotFoundException("User not found"));
        return new Caller(user.getId(), user.getPreferredLanguage().code());
    }
}
