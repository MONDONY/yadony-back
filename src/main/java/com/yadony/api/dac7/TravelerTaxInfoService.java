package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.dto.TaxInfoRequest;
import com.yadony.api.dac7.dto.TaxInfoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Informations fiscales DAC7 du voyageur (lot 3a) : lecture masquée et saisie. Le voyageur n'agit que sur son propre
 * compte (résolu depuis son jeton, aucun identifiant dans l'URL).
 *
 * <p>Confidentialité : le NIF, le pays d'émission et le numéro de TVA sont chiffrés au repos ; ils ne vont jamais dans
 * l'audit, un journal, une exception ni une réponse en clair.
 */
@Service
public class TravelerTaxInfoService {

    static final String AUDIT_ACTION = "TRAVELER_TAX_INFO_SET";
    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 40;
    private static final int VISIBLE_TAIL = 4;
    private static final String MASK = "****";
    /** Lettres et chiffres (ASCII), séparateurs internes : espace, point, tiret, barre oblique. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z0-9](?:[A-Z0-9 ./-]*[A-Z0-9])?");
    private static final Set<String> ISO_COUNTRIES = Set.copyOf(Arrays.asList(Locale.getISOCountries()));

    private final UserRepository users;
    private final AuditService audit;
    private final Dac7DueDiligenceService diligence;
    private final Dac7ResidencyPolicy residency;
    private final Dac7SolicitationPolicy solicitation;
    private final Clock clock;

    public TravelerTaxInfoService(UserRepository users, AuditService audit, Dac7DueDiligenceService diligence,
                                  Dac7ResidencyPolicy residency, Dac7SolicitationPolicy solicitation, Clock clock) {
        this.users = users;
        this.audit = audit;
        this.diligence = diligence;
        this.residency = residency;
        this.solicitation = solicitation;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TaxInfoResponse get(String firebaseUid) {
        return toResponse(load(firebaseUid));
    }

    /**
     * Enregistre les informations fiscales. Idempotent : les mêmes valeurs (après normalisation) une seconde fois
     * (double clic, nouvel envoi) ne réécrivent rien, n'auditent rien et n'ajoutent rien au registre.
     */
    @Transactional
    public TaxInfoResponse set(String firebaseUid, TaxInfoRequest request) {
        UserEntity user = load(firebaseUid);
        // Les contrôles passent avant toute écriture : une saisie refusée ne laisse rien.
        // Par HTTP, un PUT sans corps n'arrive jamais ici : Spring répond 400 malformed-request avant. Garde pour les
        // appels internes.
        if (request == null) {
            throw invalid("taxIdentificationNumber", "Le numéro d'identification fiscale est obligatoire.");
        }
        String nif = identifier(request.taxIdentificationNumber(), "taxIdentificationNumber", true);
        String country = country(request.taxCountry());
        String vat = identifier(request.vatNumber(), "vatNumber", false);

        // Double envoi : le second, s'il arrive après le premier, relit les mêmes valeurs et ne réécrit rien ; s'il
        // est strictement simultané, le verrou optimiste de users (@Version) le rejette en 409 concurrent-update et
        // sa transaction (audit et registre compris) est annulée. Jamais deux écritures.
        if (Objects.equals(nif, user.getTaxIdentificationNumber()) && Objects.equals(country, user.getTaxCountry())
                && Objects.equals(vat, user.getVatNumber())) {
            return toResponse(user);
        }
        boolean firstTime = user.getTaxIdentificationNumber() == null;
        user.setTaxIdentificationNumber(nif);
        user.setTaxCountry(country);
        user.setVatNumber(vat);
        user.setTaxInfoProvidedAt(Instant.now(clock));
        users.save(user);

        // Le fait, jamais les valeurs (ni NIF, ni pays d'émission, ni TVA) : audit_log est immuable.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("firstTime", firstTime);
        payload.put("vatNumberProvided", vat != null);
        audit.log("USER", user.getId(), AUDIT_ACTION, user.getId(), payload);
        diligence.record(user.getId(), Dac7DueDiligenceAction.PROVIDED, Dac7DueDiligenceChannel.IN_APP, null, null);
        return toResponse(user);
    }

    private UserEntity load(String firebaseUid) {
        return users.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    private TaxInfoResponse toResponse(UserEntity user) {
        String nif = user.getTaxIdentificationNumber();
        String vat = user.getVatNumber();
        return new TaxInfoResponse(nif != null, mask(nif), user.getTaxCountry(), vat != null, mask(vat),
                user.getTaxInfoProvidedAt(), Dac7ResidencyPolicy.residenceCountryOf(user), residency.statusOf(user),
                solicitation.statusOf(user));
    }

    /**
     * {@code ****} suivi d'au plus 4 derniers caractères, et jamais plus que la longueur moins 4 : un numéro court
     * ne se montre pas en entier (4 caractères → {@code ****}).
     */
    static String mask(String value) {
        if (value == null) {
            return null;
        }
        int visible = Math.max(0, Math.min(VISIBLE_TAIL, value.length() - VISIBLE_TAIL));
        return MASK + value.substring(value.length() - visible);
    }

    /** Normalise (espaces de bord retirés, majuscules) et contrôle un identifiant ; vide accepté si facultatif. */
    private static String identifier(String raw, String field, boolean required) {
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            if (required) {
                throw invalid(field, "Le numéro d'identification fiscale est obligatoire.");
            }
            return null;
        }
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw invalid(field, "Le numéro doit compter de " + MIN_LENGTH + " à " + MAX_LENGTH + " caractères.");
        }
        if (!IDENTIFIER.matcher(value).matches()) {
            throw invalid(field, "Le numéro ne peut contenir que des lettres, des chiffres, des espaces, "
                    + "des points, des tirets et des barres obliques.");
        }
        return value;
    }

    private static String country(String raw) {
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (value.length() != 2 || !ISO_COUNTRIES.contains(value)) {
            throw invalid("taxCountry", "Le pays doit être un code ISO à deux lettres (ex. FR).");
        }
        return value;
    }

    private static YadonyBusinessException invalid(String field, String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "tax-info-invalid",
                "Invalid Tax Info", detail, Map.of("field", field));
    }
}
