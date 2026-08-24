package com.yadony.api.common;

import com.google.firebase.auth.FirebaseToken;

import java.util.Map;

/**
 * Lit le fournisseur d'authentification porté par le token Firebase.
 *
 * <p>Source de vérité unique de « cette session est-elle anonyme ? ». On ne
 * duplique jamais cette information en base : Firebase en est propriétaire, et
 * après {@code linkWithCredential} l'UID ne change pas alors que le provider,
 * lui, passe de {@code anonymous} à {@code phone}. Une colonne miroir finirait
 * par diverger et bloquerait un utilisateur pourtant inscrit.
 */
public final class FirebaseSignInProvider {

    /** Valeur exacte du claim pour une session anonyme Firebase. */
    public static final String ANONYMOUS = "anonymous";

    private FirebaseSignInProvider() {
    }

    /** Valeur du claim {@code firebase.sign_in_provider}, ou {@code null}. */
    public static String of(FirebaseToken token) {
        if (token == null) {
            return null;
        }
        Object firebaseClaim = token.getClaims().get("firebase");
        if (firebaseClaim instanceof Map<?, ?> firebaseMap) {
            Object provider = firebaseMap.get("sign_in_provider");
            if (provider instanceof String s) return s;
        }
        return null;
    }

    /**
     * Vrai uniquement pour une session anonyme. Tout token absent, malformé ou
     * de provider inconnu est traité comme NON anonyme : en cas de doute on
     * n'accorde pas le statut invité, on laisse la chaîne de sécurité normale
     * décider.
     */
    public static boolean isAnonymous(FirebaseToken token) {
        return ANONYMOUS.equals(of(token));
    }
}
