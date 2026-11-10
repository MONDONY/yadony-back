package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Le voyageur est-il déclarable au titre de DAC7 ? Règle unique, calculée sur le pays de résidence fiscale déjà tenu
 * par le profil (jamais recopié) : {@code users.country}, ou le pays d'immatriculation de l'entreprise pour un
 * professionnel (DAC7 retient le siège d'une entité).
 *
 * <p>Déclarable : les 27 États membres de l'Union (France comprise) et les États ou territoires partenaires listés
 * dans {@code yadony.dac7.partner-jurisdictions} (vide par défaut, voir {@link Dac7Properties}). Un code absent ou qui
 * n'est pas un code ISO 3166-1 alpha-2 donne {@link Dac7ResidencyStatus#RESIDENCE_INCONNUE}.
 */
@Component
public class Dac7ResidencyPolicy {

    /**
     * Les 27 États membres en codes ISO 3166-1 alpha-2 (la Grèce est {@code GR}, pas {@code EL} comme dans les textes
     * européens). Les régions ultrapériphériques françaises (GP, MQ, GF, RE, YT, MF) ne sont pas proposées par le
     * catalogue des pays de résidence : non listées ici.
     */
    public static final Set<String> EU_MEMBER_STATES = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE",
            "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE");

    private static final Set<String> ISO_COUNTRIES = Set.copyOf(Arrays.asList(Locale.getISOCountries()));

    private final Set<String> partners;

    /**
     * @throws IllegalArgumentException au démarrage si un code partenaire n'est pas un code ISO alpha-2 : une faute de
     *                                  frappe ne doit pas faire disparaître des résidents de la déclaration en silence.
     */
    public Dac7ResidencyPolicy(Dac7Properties properties) {
        Set<String> codes = new HashSet<>();
        for (String raw : properties.partnerJurisdictionsOrEmpty()) {
            String code = normalize(raw);
            if (code == null) {
                throw new IllegalArgumentException("yadony.dac7.partner-jurisdictions : code pays invalide « " + raw
                        + " » (attendu : ISO 3166-1 alpha-2, ex. CI)");
            }
            codes.add(code);
        }
        this.partners = Set.copyOf(codes);
    }

    public Dac7ResidencyStatus statusOf(String residenceCountry) {
        String code = normalize(residenceCountry);
        if (code == null) {
            return Dac7ResidencyStatus.RESIDENCE_INCONNUE;
        }
        return EU_MEMBER_STATES.contains(code) || partners.contains(code)
                ? Dac7ResidencyStatus.DECLARABLE
                : Dac7ResidencyStatus.NON_DECLARABLE;
    }

    public Dac7ResidencyStatus statusOf(UserEntity user) {
        return statusOf(residenceCountryOf(user));
    }

    /** Pays de résidence fiscale du voyageur (code ISO en majuscules), {@code null} s'il est absent ou invalide. */
    public static String residenceCountryOf(UserEntity user) {
        return residenceCountryOf(user.getCountry(), user.getTravelerStatus(), user.getBusinessCountry());
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
