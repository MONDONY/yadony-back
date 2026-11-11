package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Statut de collecte DAC7 (spec du 11/11/2026, § 2.2 et 2.3), calculé au-dessus des règles existantes sans les
 * dupliquer : pays de résidence par {@link Dac7ResidencyPolicy}, périmètre transmis par {@link Dac7DgfipRules}
 * (UE27 et territoires rattachés en 2026), pièce vérifiée par {@link Dac7VerifiedIdentityPort}.
 * <ul>
 *   <li>pays inconnu : {@link Dac7CollectionStatus#RESIDENCE_INCONNUE}, seul le pays est demandé ;</li>
 *   <li>pays connu hors périmètre : {@link Dac7CollectionStatus#NE_PAS_SOLLICITER}, la pièce n'est même pas lue ;</li>
 *   <li>résident transmis, personne physique : nom, date de naissance, adresse principale complète (rue, code postal,
 *       ville) et NIF avec son pays d'émission (NIF français : exactement 13 chiffres). Chaque valeur vient de la pièce
 *       vérifiée si elle y figure (lecture seule), sinon du profil. Adresse : celle du profil si elle est complète
 *       (le voyageur l'a saisie, éventuellement pour remplacer celle de la pièce), sinon celle de la pièce si elle est
 *       complète ET dans le pays de résidence ; même ordre que {@link Dac7IdentityResolver} ;</li>
 *   <li>professionnel : seul le NIF est collecté ici, le modèle entité (raison sociale, siège...) est séparé.</li>
 * </ul>
 * Pas de valeur « NOTIN » : sans NIF le voyageur reste {@link Dac7CollectionStatus#INFO_REQUIRED}, même avec un lieu
 * de naissance (question fiscale ouverte, voir le rapport de la tâche 1). Rien n'est persisté ni journalisé.
 */
@Component
public class Dac7CollectionPolicy {

    private static final Pattern FRENCH_TIN = Pattern.compile("\\d{13}");

    private final Dac7ResidencyPolicy residency;
    private final Dac7VerifiedIdentityPort verified;
    private final Clock clock;

    @Autowired
    public Dac7CollectionPolicy(Dac7ResidencyPolicy residency, Dac7VerifiedIdentityPort verified, Clock clock) {
        this.residency = residency;
        this.verified = verified;
        this.clock = clock;
    }

    /** Évaluation complète : relit la pièce vérifiée seulement si la réponse en dépend (résident transmis, particulier). */
    public Dac7Collection evaluate(UserEntity user) {
        return evaluate(user, needsVerifiedIdentity(user) ? verified.verifiedIdentity(user.getId()) : null);
    }

    /**
     * La pièce vérifiée a-t-elle un sens pour ce voyageur ? Seulement pour un particulier résident d'un État transmis :
     * jamais d'appel au prestataire pour un résident hors Union ni pour un pays inconnu.
     */
    public boolean needsVerifiedIdentity(UserEntity user) {
        return scope(user) == Scope.INDIVIDUAL;
    }

    /** Lecture de la pièce déjà faite (ou volontairement omise : {@link Dac7VerifiedIdentityPort.Lookup#none()}). */
    public Dac7Collection evaluate(UserEntity user, Dac7VerifiedIdentityPort.Lookup lookup) {
        String residence = Dac7ResidencyPolicy.residenceCountryOf(user);
        Scope scope = scope(user);
        if (scope == Scope.UNKNOWN_RESIDENCE) {
            return Dac7Collection.withoutIdentity(Dac7CollectionStatus.RESIDENCE_INCONNUE,
                    List.of(Dac7CollectionField.RESIDENCE_COUNTRY), null);
        }
        if (scope == Scope.OUT_OF_SCOPE) {
            return Dac7Collection.withoutIdentity(Dac7CollectionStatus.NE_PAS_SOLLICITER, List.of(), residence);
        }
        boolean tinMissing = !validTaxIdentifier(user.getTaxIdentificationNumber(), user.getTaxCountry());
        if (scope == Scope.ENTITY) {
            List<Dac7CollectionField> missing = tinMissing
                    ? List.of(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER) : List.of();
            return Dac7Collection.withoutIdentity(status(missing), missing, residence);
        }

        boolean unavailable = lookup == null || lookup.status() == Dac7VerifiedIdentityPort.Status.UNAVAILABLE;
        Dac7VerifiedIdentity piece = lookup != null && lookup.status() == Dac7VerifiedIdentityPort.Status.FOUND
                ? lookup.identity() : null;
        List<Dac7CollectionField> missing = new ArrayList<>();

        String first;
        String last;
        Dac7FieldSource nameSource;
        if (piece != null && !blank(piece.firstName()) && !blank(piece.lastName())) {
            first = piece.firstName().strip();
            last = piece.lastName().strip();
            nameSource = Dac7FieldSource.DIDIT;
        } else if (!blank(user.getFirstName()) && !blank(user.getLastName())) {
            first = user.getFirstName().strip();
            last = user.getLastName().strip();
            nameSource = Dac7FieldSource.PROFILE;
        } else {
            first = null;
            last = null;
            nameSource = null;
            missing.add(Dac7CollectionField.NAME);
        }

        LocalDate birth;
        Dac7FieldSource birthSource;
        if (piece != null && piece.birthDate() != null) {
            birth = piece.birthDate();
            birthSource = Dac7FieldSource.DIDIT;
        } else if (user.getBirthDate() != null) {
            birth = user.getBirthDate();
            birthSource = Dac7FieldSource.PROFILE;
        } else {
            birth = null;
            birthSource = null;
            missing.add(Dac7CollectionField.BIRTH_DATE);
        }

        Dac7Address address = profileAddress(user, residence);
        Dac7FieldSource addressSource = address != null ? Dac7FieldSource.PROFILE : null;
        if (address == null && piece != null && complete(piece.address())
                && residence.equals(piece.address().countryCode())) {
            address = piece.address();
            addressSource = Dac7FieldSource.DIDIT;
        }
        if (address == null) {
            missing.add(Dac7CollectionField.ADDRESS);
        }
        if (tinMissing) {
            missing.add(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER);
        }
        boolean fromPiece = nameSource == Dac7FieldSource.DIDIT || birthSource == Dac7FieldSource.DIDIT
                || addressSource == Dac7FieldSource.DIDIT;
        return new Dac7Collection(status(missing), missing, residence, true, first, last, nameSource, birth,
                birthSource, address, addressSource, unavailable, fromPiece ? piece.verificationRef() : null);
    }

    /**
     * NIF enregistrable et suffisant : pays d'émission présent ; pour la France exactement 13 chiffres (espaces
     * ignorés), ailleurs non vide. Pas de valeur par défaut.
     */
    public static boolean validTaxIdentifier(String tin, String issuingCountry) {
        if (blank(tin) || blank(issuingCountry)) {
            return false;
        }
        if ("FR".equals(issuingCountry.strip())) {
            return FRENCH_TIN.matcher(tin.replace(" ", "")).matches();
        }
        return true;
    }

    private enum Scope { UNKNOWN_RESIDENCE, OUT_OF_SCOPE, INDIVIDUAL, ENTITY }

    private Scope scope(UserEntity user) {
        Dac7ResidencyStatus status = residency.statusOf(user);
        if (status == Dac7ResidencyStatus.RESIDENCE_INCONNUE) {
            return Scope.UNKNOWN_RESIDENCE;
        }
        int year = LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear();
        if (status != Dac7ResidencyStatus.DECLARABLE
                || !Dac7DgfipRules.transmitted(year, Dac7ResidencyPolicy.residenceCountryOf(user))) {
            return Scope.OUT_OF_SCOPE;
        }
        TravelerStatus seller = user.getTaxSellerStatus() != null ? user.getTaxSellerStatus()
                : user.getTravelerStatus();
        return seller == TravelerStatus.PROFESSIONAL ? Scope.ENTITY : Scope.INDIVIDUAL;
    }

    private static Dac7CollectionStatus status(List<Dac7CollectionField> missing) {
        return missing.isEmpty() ? Dac7CollectionStatus.COMPLETE : Dac7CollectionStatus.INFO_REQUIRED;
    }

    /** Adresse du profil si elle est complète (rue, code postal, ville) ; pays = pays de résidence. */
    private static Dac7Address profileAddress(UserEntity user, String residence) {
        if (blank(user.getResidenceStreet()) || blank(user.getResidencePostalCode()) || blank(user.getCity())) {
            return null;
        }
        return new Dac7Address(user.getResidenceStreet().strip(), trim(user.getResidenceLine2()),
                user.getResidencePostalCode().strip(), user.getCity().strip(), residence, null);
    }

    private static boolean complete(Dac7Address address) {
        return address != null && !blank(address.street()) && !blank(address.postCode()) && !blank(address.city())
                && address.countryCode() != null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return blank(value) ? null : value.strip();
    }
}
