package com.yadony.api.auth.dto;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Set;

/**
 * Champs modifiables du profil. L'email n'en fait volontairement pas partie :
 * c'est une donnée d'identification portée par Firebase Auth, pas un champ de
 * profil. Le laisser modifiable ici permettrait de détourner l'adresse d'un
 * compte depuis une simple requête de profil.
 */
public record UpdateProfileRequest(
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    @Past LocalDate birthDate,
    @Size(max = 100) String city,
    @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "Format E.164 requis (ex: +33612345678)")
    String phoneNumber,
    @Size(max = 280) String bio,
    Set<String> languages
) {}
