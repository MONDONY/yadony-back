package com.yadony.api.auth;

import org.springframework.stereotype.Component;

/**
 * Point unique qui décide quel numéro déclaré (et quel niveau) un compte peut exposer.
 *
 * <p>Le niveau stocké en base n'est digne de confiance que si l'empreinte du claim Firebase
 * courant correspond à {@code declaredPhoneHash} : sinon le numéro a changé depuis la
 * vérification (ou la clé d'empreinte manque) et on retombe à {@link DeclaredPhoneLevel#DECLARED}.
 * Un numéro vérifié par SMS l'emporte toujours : le déclaré est alors ignoré.
 */
@Component
public class DeclaredPhoneResolver {

    /** Numéro et niveau exposables ; les deux sont nuls quand rien n'est à exposer. */
    public record Resolved(String number, DeclaredPhoneLevel level) {
        public static final Resolved NONE = new Resolved(null, null);
    }

    private final DeclaredPhoneHasher hasher;

    public DeclaredPhoneResolver(DeclaredPhoneHasher hasher) {
        this.hasher = hasher;
    }

    public Resolved resolve(UserEntity user, FirebaseContactService.Contact contact) {
        if (contact == null || isBlank(contact.declaredPhone()) || !isBlank(contact.phoneNumber())) {
            return Resolved.NONE;
        }
        String claim = contact.declaredPhone();
        boolean trusted = user.getDeclaredPhoneLevel() == DeclaredPhoneLevel.YADONY_VERIFIED
                && hasher.isConfigured()
                && user.getDeclaredPhoneHash() != null
                && user.getDeclaredPhoneHash().equals(hasher.hash(claim));
        return new Resolved(claim, trusted ? DeclaredPhoneLevel.YADONY_VERIFIED : DeclaredPhoneLevel.DECLARED);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
