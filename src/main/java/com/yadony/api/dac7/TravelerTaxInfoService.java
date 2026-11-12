package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.dto.TaxInfoRequest;
import com.yadony.api.dac7.dto.TaxInfoResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Informations fiscales DAC7 du voyageur : lecture (NIF masqué, statut de collecte ciblée) et saisie. Le voyageur
 * n'agit que sur son propre compte (résolu depuis son jeton, aucun identifiant dans l'URL).
 *
 * <p>Collecte ciblée (spec du 11/11/2026) : le statut et les champs manquants viennent de {@link Dac7CollectionPolicy}.
 * Les valeurs reprises de la pièce vérifiée (Didit) sont en lecture seule : une saisie différente est refusée (422
 * {@code tax-info-read-only}), sauf l'adresse, qu'une saisie remplace (« Ce n'est plus mon adresse »). Nom, date de
 * naissance et adresse saisis vont dans les colonnes du profil (mêmes données, une seule version) ; le lieu de
 * naissance, demandé seulement sans NIF, est chiffré. Jamais bloquant : rien d'autre ne dépend de cette saisie.
 *
 * <p>Transactions : la pièce vérifiée est relue chez le prestataire AVANT la transaction d'écriture, jamais en tenant une
 * connexion JDBC (YADONY-BACK-STAGING-W, {@code open-in-view: false}) ; la lecture n'a pas de transaction englobante.
 *
 * <p>Confidentialité : le NIF, le pays d'émission, le numéro de TVA et le lieu de naissance sont chiffrés au repos ;
 * aucune valeur ne va dans l'audit, un journal, le registre, une exception ni une réponse en clair (le NIF et la TVA
 * ne sortent que masqués).
 */
@Service
public class TravelerTaxInfoService {

    static final String AUDIT_ACTION = "TRAVELER_TAX_INFO_SET";
    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 40;
    private static final int VISIBLE_TAIL = 4;
    private static final int MAX_NAME = 100;
    private static final int MAX_STREET = 255;
    private static final int MAX_LINE2 = 100;
    private static final int MAX_POSTAL_CODE = 20;
    private static final int MAX_CITY = 100;
    static final int MAX_BIRTH_PLACE = 120;
    private static final LocalDate OLDEST_BIRTH = LocalDate.of(1900, 1, 1);
    private static final String MASK = "****";
    private static final String BIRTH_PLACE = "BIRTH_PLACE";
    /** Lettres et chiffres (ASCII), séparateurs internes : espace, point, tiret, barre oblique. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z0-9](?:[A-Z0-9 ./-]*[A-Z0-9])?");
    private static final Set<String> ISO_COUNTRIES = Set.copyOf(Arrays.asList(Locale.getISOCountries()));

    private final UserRepository users;
    private final AuditService audit;
    private final Dac7DueDiligenceService diligence;
    private final Dac7ResidencyPolicy residency;
    private final Dac7SolicitationPolicy solicitation;
    private final Dac7CollectionPolicy collection;
    private final Clock clock;
    private final TransactionOperations writes;

    @Autowired
    public TravelerTaxInfoService(UserRepository users, AuditService audit, Dac7DueDiligenceService diligence,
                                  Dac7ResidencyPolicy residency, Dac7SolicitationPolicy solicitation,
                                  Dac7CollectionPolicy collection, Clock clock,
                                  PlatformTransactionManager transactionManager) {
        this(users, audit, diligence, residency, solicitation, collection, clock,
                new TransactionTemplate(transactionManager));
    }

    /** Tests : transaction fournie (ou {@link TransactionOperations#withoutTransaction()}). */
    TravelerTaxInfoService(UserRepository users, AuditService audit, Dac7DueDiligenceService diligence,
                           Dac7ResidencyPolicy residency, Dac7SolicitationPolicy solicitation,
                           Dac7CollectionPolicy collection, Clock clock, TransactionOperations writes) {
        this.users = users;
        this.audit = audit;
        this.diligence = diligence;
        this.residency = residency;
        this.solicitation = solicitation;
        this.collection = collection;
        this.clock = clock;
        this.writes = writes;
    }

    /** Sans transaction englobante : la pièce vérifiée peut être relue chez le prestataire. */
    public TaxInfoResponse get(String firebaseUid) {
        UserEntity user = load(firebaseUid);
        return toResponse(user, collection.evaluate(user));
    }

    /**
     * Enregistre les informations envoyées (seulement celles-là). Idempotent : les mêmes valeurs (après normalisation)
     * une seconde fois (double clic, nouvel envoi) ne réécrivent rien, n'auditent rien et n'ajoutent rien au registre.
     * La pièce vérifiée est relue d'abord, hors transaction ; contrôles et écritures suivent dans une seule transaction.
     */
    public TaxInfoResponse set(String firebaseUid, TaxInfoRequest request) {
        // Par HTTP, un PUT sans corps n'arrive jamais ici : Spring répond 400 malformed-request avant. Garde pour les
        // appels internes.
        if (request == null) {
            throw invalid("taxIdentificationNumber", "problem.dac7.tax-info-invalid.tax-id-required");
        }
        UserEntity current = load(firebaseUid);
        // Pays en cours de déclaration : la pièce est utile dès cette réponse (lecture en cache, délai borné).
        Dac7VerifiedIdentityPort.Lookup lookup = request.residenceCountry() != null
                ? collection.lookupAnyway(current) : collection.lookup(current);
        return writes.execute(status -> write(firebaseUid, request, lookup));
    }

    private TaxInfoResponse write(String firebaseUid, TaxInfoRequest request, Dac7VerifiedIdentityPort.Lookup lookup) {
        UserEntity user = load(firebaseUid);
        // Les contrôles passent avant toute écriture : une saisie refusée ne laisse rien (la transaction est annulée).
        boolean tinGiven = !blank(request.taxIdentificationNumber());
        boolean nothingElse = !request.hasIdentityFields() && request.vatNumber() == null
                && request.residenceCountry() == null;
        if (!tinGiven && (nothingElse || !blank(request.taxCountry()))) {
            // Ancien contrat : sans NIF ni autre champ, le NIF est obligatoire ; un pays d'émission seul aussi.
            throw invalid("taxIdentificationNumber", "problem.dac7.tax-info-invalid.tax-id-required");
        }
        String nif = user.getTaxIdentificationNumber();
        String country = user.getTaxCountry();
        String vat = user.getVatNumber();
        if (tinGiven) {
            country = country(request.taxCountry());
            nif = taxIdentifier(request.taxIdentificationNumber(), country);
            vat = identifier(request.vatNumber(), "vatNumber", false);
        } else if (request.vatNumber() != null) {
            vat = identifier(request.vatNumber(), "vatNumber", false);
        }

        Set<String> changed = new LinkedHashSet<>();
        if (request.residenceCountry() != null) {
            String declared = residenceCountry(user, request.residenceCountry());
            if (!declared.equals(user.getTaxDeclaredResidenceCountry())) {
                // Écrit avant l'évaluation : les champs d'identité de la même requête sont jugés avec ce pays. Une
                // erreur plus loin annule la transaction entière.
                changed.add(Dac7CollectionField.RESIDENCE_COUNTRY.name());
                user.setTaxDeclaredResidenceCountry(declared);
            }
        }
        Dac7Collection before = collection.evaluate(user, lookup);
        Identity identity = request.hasIdentityFields() ? identity(request, before, nif) : null;

        // Double envoi : le second, s'il arrive après le premier, relit les mêmes valeurs et ne réécrit rien ; s'il
        // est strictement simultané, le verrou optimiste de users (@Version) le rejette en 409 concurrent-update et
        // sa transaction (audit et registre compris) est annulée. Jamais deux écritures.
        boolean firstTime = user.getTaxIdentificationNumber() == null && nif != null;
        if (!Objects.equals(nif, user.getTaxIdentificationNumber())
                || !Objects.equals(country, user.getTaxCountry())) {
            changed.add(Dac7CollectionField.TAX_IDENTIFICATION_NUMBER.name());
            user.setTaxIdentificationNumber(nif);
            user.setTaxCountry(country);
        }
        if (!Objects.equals(vat, user.getVatNumber())) {
            changed.add("VAT_NUMBER");
            user.setVatNumber(vat);
        }
        if (identity != null) {
            identity.apply(user, changed);
        }
        // Le lieu de naissance ne remplace que l'absence de NIF : inutile dès qu'un NIF existe (minimisation).
        if (user.getTaxIdentificationNumber() != null && user.getTaxBirthPlace() != null) {
            changed.add(BIRTH_PLACE);
            user.setTaxBirthPlace(null);
        }
        if (changed.isEmpty()) {
            return toResponse(user, before);
        }
        user.setTaxInfoProvidedAt(Instant.now(clock));
        users.save(user);

        // Le fait et les codes des champs, jamais les valeurs : audit_log est immuable.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("firstTime", firstTime);
        payload.put("vatNumberProvided", user.getVatNumber() != null);
        payload.put("fields", List.copyOf(changed));
        audit.log("USER", user.getId(), AUDIT_ACTION, user.getId(), payload);
        diligence.recordProvidedByUser(user.getId(), String.join(",", changed));

        Dac7Collection after = collection.evaluate(user, lookup);
        diligence.recordVerifiedSource(user.getId(), after);
        return toResponse(user, after);
    }

    private UserEntity load(String firebaseUid) {
        return users.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
    }

    private TaxInfoResponse toResponse(UserEntity user, Dac7Collection c) {
        String nif = user.getTaxIdentificationNumber();
        String vat = user.getVatNumber();
        return new TaxInfoResponse(nif != null, mask(nif), user.getTaxCountry(), vat != null, mask(vat),
                user.getTaxInfoProvidedAt(), Dac7ResidencyPolicy.residenceCountryOf(user), residency.statusOf(user),
                solicitation.statusOf(user), c.status(), c.missing(), c.readOnlyFields(), c.actionRequired(),
                user.getTaxInfoRequestedAt(), identityView(user, c),
                c.status() == Dac7CollectionStatus.RESIDENCE_INCONNUE ? Dac7ResidenceCountries.CHOICES : null);
    }

    /**
     * Pays de résidence déclaré pour DAC7 : seulement quand le profil n'a pas de pays (le pays du profil, qui pilote
     * aussi les paiements, se change dans le profil) et parmi {@link Dac7ResidenceCountries#CHOICES}.
     */
    private static String residenceCountry(UserEntity user, String raw) {
        String code = Dac7ResidenceCountries.normalize(raw);
        if (code == null) {
            throw invalid("residenceCountry", "problem.dac7.tax-info-invalid.country-unavailable");
        }
        if (user.getCountry() != null) {
            throw invalid("residenceCountry", "problem.dac7.tax-info-invalid.residence-country-in-profile");
        }
        return code;
    }

    private static TaxInfoResponse.TaxIdentity identityView(UserEntity user, Dac7Collection c) {
        if (!c.identityKnown()) {
            return null;
        }
        Dac7Address a = c.address();
        TaxInfoResponse.TaxAddress address = a == null ? null
                : new TaxInfoResponse.TaxAddress(a.street(), a.line2(), a.postCode(), a.city(), a.countryCode());
        return new TaxInfoResponse.TaxIdentity(c.firstName(), c.lastName(), c.nameSource(), c.birthDate(),
                c.birthDateSource(), address, c.addressSource(), c.addressSource() == Dac7FieldSource.DIDIT,
                user.getTaxBirthPlace() != null, c.identityUnavailable() ? Boolean.TRUE : null);
    }

    /** Champs d'identité contrôlés, prêts à écrire ; nul = champ non envoyé (ou égal à la pièce vérifiée). */
    private record Identity(String firstName, String lastName, LocalDate birthDate, TaxInfoRequest.AddressInput address,
                            String birthPlace, boolean clearBirthPlace) {

        void apply(UserEntity user, Set<String> changed) {
            if (firstName != null && (!firstName.equals(user.getFirstName()) || !lastName.equals(user.getLastName()))) {
                changed.add(Dac7CollectionField.NAME.name());
                user.setFirstName(firstName);
                user.setLastName(lastName);
            }
            if (birthDate != null && !birthDate.equals(user.getBirthDate())) {
                changed.add(Dac7CollectionField.BIRTH_DATE.name());
                user.setBirthDate(birthDate);
            }
            if (address != null && (!address.street().equals(user.getResidenceStreet())
                    || !Objects.equals(address.line2(), user.getResidenceLine2())
                    || !address.postalCode().equals(user.getResidencePostalCode())
                    || !address.city().equals(user.getCity()))) {
                changed.add(Dac7CollectionField.ADDRESS.name());
                user.setResidenceStreet(address.street());
                user.setResidenceLine2(address.line2());
                user.setResidencePostalCode(address.postalCode());
                user.setCity(address.city());
            }
            if (birthPlace != null && !birthPlace.equals(user.getTaxBirthPlace())
                    || clearBirthPlace && user.getTaxBirthPlace() != null) {
                changed.add(BIRTH_PLACE);
                user.setTaxBirthPlace(birthPlace);
            }
        }
    }

    /**
     * Contrôle les champs d'identité. Seulement pour un particulier résident de l'Union (on ne collecte rien aux
     * autres) ; une valeur reprise de la pièce vérifiée ne se modifie pas, sauf l'adresse.
     */
    private Identity identity(TaxInfoRequest request, Dac7Collection before, String nifAfter) {
        if (!before.identityKnown()) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "tax-info-not-applicable", "problem.dac7.tax-info-not-applicable");
        }
        String first = null;
        String last = null;
        if (request.firstName() != null || request.lastName() != null) {
            first = text(request.firstName(), "firstName", MAX_NAME, true);
            last = text(request.lastName(), "lastName", MAX_NAME, true);
            if (before.nameSource() == Dac7FieldSource.DIDIT) {
                if (!first.equalsIgnoreCase(before.firstName()) || !last.equalsIgnoreCase(before.lastName())) {
                    throw readOnly("firstName", "problem.dac7.tax-info-read-only.name-from-id");
                }
                first = null;
                last = null;
            }
        }
        LocalDate birth = null;
        if (request.birthDate() != null) {
            birth = birthDate(request.birthDate());
            if (before.birthDateSource() == Dac7FieldSource.DIDIT) {
                if (!birth.equals(before.birthDate())) {
                    throw readOnly("birthDate", "problem.dac7.tax-info-read-only.birthdate-from-id");
                }
                birth = null;
            }
        }
        TaxInfoRequest.AddressInput address = request.address() == null ? null : address(request.address());
        String birthPlace = null;
        boolean clearBirthPlace = false;
        if (request.birthPlace() != null) {
            if (request.birthPlace().isBlank()) {
                clearBirthPlace = true;
            } else if (nifAfter != null) {
                throw invalid("birthPlace", "problem.dac7.tax-info-invalid.birthplace-only-without-tax-id");
            } else {
                birthPlace = text(request.birthPlace(), "birthPlace", MAX_BIRTH_PLACE, true);
            }
        }
        return new Identity(first, last, birth, address, birthPlace, clearBirthPlace);
    }

    private LocalDate birthDate(String raw) {
        LocalDate date;
        try {
            date = LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw invalid("birthDate", "problem.dac7.tax-info-invalid.birthdate-format");
        }
        if (date.isBefore(OLDEST_BIRTH) || date.isAfter(LocalDate.now(clock))) {
            throw invalid("birthDate", "problem.dac7.tax-info-invalid.birthdate-invalid");
        }
        return date;
    }

    private static TaxInfoRequest.AddressInput address(TaxInfoRequest.AddressInput raw) {
        return new TaxInfoRequest.AddressInput(text(raw.street(), "address.street", MAX_STREET, true),
                text(raw.line2(), "address.line2", MAX_LINE2, false),
                text(raw.postalCode(), "address.postalCode", MAX_POSTAL_CODE, true),
                text(raw.city(), "address.city", MAX_CITY, true));
    }

    /** Texte libre nettoyé (espaces de bord), borné ; vide accepté (nul) si facultatif. Jamais recopié en erreur. */
    private static String text(String raw, String field, int max, boolean required) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            if (required) {
                throw invalid(field, "problem.dac7.tax-info-invalid.field-required");
            }
            return null;
        }
        if (value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field, "problem.dac7.tax-info-invalid.field-too-long", String.valueOf(max));
        }
        return value;
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

    /**
     * NIF : règle générale, puis la règle du pays d'émission ({@link Dac7CollectionPolicy#validTaxIdentifier}) ; un NIF
     * français (13 chiffres) est gardé sans espace.
     */
    private static String taxIdentifier(String raw, String issuingCountry) {
        String value = identifier(raw, "taxIdentificationNumber", true);
        if (!Dac7CollectionPolicy.validTaxIdentifier(value, issuingCountry)) {
            throw invalid("taxIdentificationNumber", "problem.dac7.tax-info-invalid.fr-tax-id-length");
        }
        return "FR".equals(issuingCountry) ? value.replace(" ", "") : value;
    }

    /** Normalise (espaces de bord retirés, majuscules) et contrôle un identifiant ; vide accepté si facultatif. */
    private static String identifier(String raw, String field, boolean required) {
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            if (required) {
                throw invalid(field, "problem.dac7.tax-info-invalid.tax-id-required");
            }
            return null;
        }
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw invalid(field, "problem.dac7.tax-info-invalid.tax-id-length", String.valueOf(MIN_LENGTH), String.valueOf(MAX_LENGTH));
        }
        if (!IDENTIFIER.matcher(value).matches()) {
            throw invalid(field, "problem.dac7.tax-info-invalid.tax-id-charset");
        }
        return value;
    }

    private static String country(String raw) {
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (value.length() != 2 || !ISO_COUNTRIES.contains(value)) {
            throw invalid("taxCountry", "problem.dac7.tax-info-invalid.tax-country-iso");
        }
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static YadonyBusinessException invalid(String field, String messageKey, Object... args) {
        return YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "tax-info-invalid",
                messageKey, Map.of("field", field), args);
    }

    private static YadonyBusinessException readOnly(String field, String messageKey, Object... args) {
        return YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "tax-info-read-only",
                messageKey, Map.of("field", field), args);
    }
}
