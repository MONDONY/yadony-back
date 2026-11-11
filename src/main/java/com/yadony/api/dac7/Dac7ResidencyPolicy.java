package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Le voyageur est-il déclarable au titre de DAC7 ? Règle unique, calculée sur le pays de résidence fiscale déjà tenu
 * par le profil (jamais recopié) : {@code users.country}, ou le pays d'immatriculation de l'entreprise pour un
 * professionnel (DAC7 retient le siège d'une entité). Sans pays au profil : le pays déclaré pour DAC7
 * ({@code users.tax_declared_residence_country}, collecte ciblée), en repli seulement.
 *
 * <p>Déclarable : les 27 États membres de l'Union (France comprise), leurs territoires à code ISO propre et les États ou territoires partenaires listés
 * dans {@code yadony.dac7.partner-jurisdictions} (vide par défaut, voir {@link Dac7Properties}). Un code absent ou qui
 * n'est pas un code ISO 3166-1 alpha-2 donne {@link Dac7ResidencyStatus#RESIDENCE_INCONNUE}.
 */
@Component
public class Dac7ResidencyPolicy {

    private static final Logger log = LoggerFactory.getLogger(Dac7ResidencyPolicy.class);

    /** Les 27 États membres en codes ISO 3166-1 alpha-2 (la Grèce est {@code GR}, pas {@code EL}). */
    public static final Set<String> EU_MEMBER_STATES = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE",
            "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE");

    /**
     * Territoires de l'Union qui ont leur propre code ISO : régions ultrapériphériques françaises (Guadeloupe,
     * Martinique, Guyane, La Réunion, Mayotte, Saint-Martin) et Åland (Finlande). Le catalogue des pays de résidence ne
     * les propose pas aujourd'hui ; listés en défense, pour qu'un tel code venu d'ailleurs (Stripe, évolution du
     * catalogue) ne tombe jamais en « non déclarable » en silence.
     */
    public static final Set<String> EU_TERRITORIES = Set.of("GP", "MQ", "GF", "RE", "YT", "MF", "AX");

    private static final Set<String> ISO_COUNTRIES = Set.copyOf(Arrays.asList(Locale.getISOCountries()));

    private final Set<String> partners;
    private final List<String> invalidPartners;

    /**
     * Codes partenaires invalides : DAC7 allumé, le démarrage échoue (une faute de frappe ne doit pas retirer des
     * résidents de la déclaration en silence) ; DAC7 éteint, le code est ignoré avec un avertissement et reste visible
     * dans {@link #invalidPartnerJurisdictions()} (le back démarre quand même).
     */
    public Dac7ResidencyPolicy(Dac7Properties properties) {
        Set<String> codes = new HashSet<>();
        List<String> invalid = new ArrayList<>();
        for (String raw : properties.partnerJurisdictionsOrEmpty()) {
            String code = normalize(raw);
            if (code == null) {
                invalid.add(String.valueOf(raw));
            } else {
                codes.add(code);
            }
        }
        if (!invalid.isEmpty()) {
            String message = "yadony.dac7.partner-jurisdictions : code(s) pays invalide(s) " + invalid
                    + " (attendu : ISO 3166-1 alpha-2, ex. CI)";
            if (properties.enabledOrDefault()) {
                throw new IllegalArgumentException(message);
            }
            log.warn("{} : ignoré(s) tant que DAC7 est éteint", message);
        }
        this.partners = Set.copyOf(codes);
        this.invalidPartners = List.copyOf(invalid);
    }

    /** Codes partenaires ignorés car invalides (DAC7 éteint) ; vide si la configuration est correcte. */
    public List<String> invalidPartnerJurisdictions() {
        return invalidPartners;
    }

    public Dac7ResidencyStatus statusOf(String residenceCountry) {
        String code = normalize(residenceCountry);
        if (code == null) {
            return Dac7ResidencyStatus.RESIDENCE_INCONNUE;
        }
        return EU_MEMBER_STATES.contains(code) || EU_TERRITORIES.contains(code) || partners.contains(code)
                ? Dac7ResidencyStatus.DECLARABLE
                : Dac7ResidencyStatus.NON_DECLARABLE;
    }

    public Dac7ResidencyStatus statusOf(UserEntity user) {
        return statusOf(residenceCountryOf(user));
    }

    /**
     * Pays de résidence fiscale du voyageur (code ISO en majuscules), {@code null} s'il est absent ou invalide. Pour un
     * compte supprimé dont le NIF est gardé, le pays figé à la suppression ({@code tax_residence_country}) l'emporte.
     */
    public static String residenceCountryOf(UserEntity user) {
        return residenceCountryOf(user.getTaxResidenceCountry(),
                profileOrDeclared(user.getCountry(), user.getTaxDeclaredResidenceCountry()), user.getTravelerStatus(),
                user.getBusinessCountry());
    }

    /**
     * Pays du profil s'il est valide, sinon le pays déclaré pour DAC7 ({@code tax_declared_residence_country}, collecte
     * ciblée) : simple repli, le profil l'emporte toujours. Même règle que {@code COALESCE(u.country,
     * u.tax_declared_residence_country)} dans les lectures SQL.
     */
    public static String profileOrDeclared(String profileCountry, String declaredCountry) {
        return profileCountry != null ? profileCountry : declaredCountry;
    }

    public static String residenceCountryOf(String frozenResidenceCountry, String profileCountry, TravelerStatus status,
                                            String businessCountry) {
        String frozen = normalize(frozenResidenceCountry);
        return frozen != null ? frozen : residenceCountryOf(profileCountry, status, businessCountry);
    }

    public static String residenceCountryOf(String profileCountry, TravelerStatus status, String businessCountry) {
        if (status == TravelerStatus.PROFESSIONAL && normalize(businessCountry) != null) {
            return normalize(businessCountry);
        }
        return normalize(profileCountry);
    }

    /** Code ISO alpha-2 valide en majuscules, sinon {@code null}. */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String code = raw.strip().toUpperCase(Locale.ROOT);
        return code.length() == 2 && ISO_COUNTRIES.contains(code) ? code : null;
    }
}
