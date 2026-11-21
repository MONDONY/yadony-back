package com.yadony.api.auth;

/**
 * Issue de la recherche d'un compte par numéro ({@link PhoneLookupService}). {@link Ambiguous}
 * ne porte aucun compte : un numéro déclaré par plusieurs personnes ne désigne personne, les
 * appelants ne doivent ni choisir ni révéler lequel.
 */
public sealed interface PhoneMatch {

    record Found(UserEntity user) implements PhoneMatch {
    }

    record Ambiguous() implements PhoneMatch {
    }

    record None() implements PhoneMatch {
    }
}
