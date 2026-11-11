package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.Dac7DeclarationService.Declaration;
import com.yadony.api.dac7.Dac7DeclarationService.Other;
import com.yadony.api.dac7.Dac7DeclarationService.Seller;
import com.yadony.api.dac7.Dac7Report.QuarterReport;
import com.yadony.api.dac7.export.Dac7FileEncryptor;
import com.yadony.api.dac7.export.Dac7FileNaming;
import com.yadony.api.dac7.export.Dac7XmlDocument;
import com.yadony.api.dac7.export.Dac7XmlSeller;
import com.yadony.api.dac7.export.Dac7XmlWriter;
import com.yadony.api.dac7.export.Dac7XsdValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fichier de déclaration DPI-DAC7 et CSV de contrôle (lot 3b, contrat 2). Le fichier est généré à la demande de
 * l'équipe et rendu à l'admin : il n'est JAMAIS envoyé à la DGFiP par le système (dépôt Télé-TD, geste humain).
 *
 * <ul>
 *   <li>{@code FULL} : refusé (409 {@code dac7-export-blocked}) tant qu'un blocage existe ;</li>
 *   <li>{@code PARTIAL} : seulement les vendeurs complets ; les autres sont comptés dans {@code X-Dac7-Excluded} et
 *       listés, avec leur raison, dans le CSV de contrôle.</li>
 * </ul>
 * Le NIF et la TVA ne sont lus en clair qu'ici, le temps d'écrire le XML ; ils ne vont dans aucun journal, aucun audit
 * ni aucun message d'erreur (le validateur ne rapporte que la ligne et la colonne).
 */
@Service
public class Dac7ExportService {

    private static final Logger log = LoggerFactory.getLogger(Dac7ExportService.class);
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    public enum Mode { FULL, PARTIAL }

    public record ExportFile(String fileName, byte[] content, boolean encrypted, int sellers, int excluded) {
    }

    private final Dac7DeclarationService declarations;
    private final UserRepository users;
    private final Dac7ExportProperties properties;
    private final AuditService audit;
    private final Clock clock;

    @Autowired
    public Dac7ExportService(Dac7DeclarationService declarations, UserRepository users,
                             Dac7ExportProperties properties, AuditService audit) {
        this(declarations, users, properties, audit, Clock.systemUTC());
    }

    Dac7ExportService(Dac7DeclarationService declarations, UserRepository users, Dac7ExportProperties properties,
                      AuditService audit, Clock clock) {
        this.declarations = declarations;
        this.users = users;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    public ExportFile export(Integer yearValue, String modeValue, Integer sequenceValue, UUID adminId) {
        int year = declarations.requireYear(yearValue);
        Mode mode = mode(modeValue);
        int sequence = sequenceValue == null ? 0 : sequenceValue;
        if (sequence < 0 || sequence > 999) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-export-invalid-request",
                    "Invalid Export Request", "Le numéro d'ordre doit être compris entre 0 et 999.");
        }
        Declaration declaration = declarations.declaration(year, true);
        if (!properties.operatorConfigured()) {
            throw blocked(List.of(Dac7DeclarationService.OPERATOR_CONFIG_MISSING));
        }
        if (mode == Mode.FULL && !declaration.blockers().isEmpty()) {
            throw blocked(declaration.blockers());
        }
        List<Seller> included = declaration.sellers().stream().filter(s -> s.exclusions().isEmpty()).toList();
        int excluded = declaration.sellers().size() - included.size();
        if (included.isEmpty()) {
            List<String> blockers = new ArrayList<>(declaration.blockers());
            if (!blockers.contains(Dac7DeclarationService.NO_SELLERS)) {
                blockers.add(Dac7DeclarationService.NO_SELLERS);
            }
            throw blocked(blockers);
        }

        Map<UUID, UserEntity> byId = users.findAllById(included.stream().map(Seller::userId).toList()).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        LocalDateTime createdAt = LocalDateTime.now(clock.withZone(PARIS)).withNano(0);
        String unique = createdAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "_"
                + shortId();
        List<Dac7XmlDocument.Seller> sellers = new ArrayList<>();
        for (Seller seller : included) {
            UserEntity user = byId.get(seller.userId());
            if (user == null || seller.identity() == null) {
                // Compte supprimé entre le calcul et la lecture : jamais déclaré à moitié.
                throw blocked(List.of(Dac7DeclarationService.IDENTITY_INCOMPLETE));
            }
            sellers.add(new Dac7XmlDocument.Seller(xmlSeller(seller, user), shortId()));
        }
        Dac7XmlDocument document = new Dac7XmlDocument(year, createdAt, unique, operator(), sellers);
        byte[] xml = Dac7XmlWriter.write(document);
        try {
            Dac7XsdValidator.validate(xml);
        } catch (Dac7XsdValidator.InvalidFileException e) {
            // Ligne et colonne seulement : le message du validateur recopierait la valeur fautive.
            log.warn("Export DAC7 {} non conforme au schéma DGFiP (ligne {}, colonne {})", year, e.line(), e.column());
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-export-invalid",
                    "Invalid Export", "Le fichier généré n'est pas conforme au schéma de la DGFiP (ligne " + e.line()
                    + "). Vérifiez les identités des voyageurs concernés.");
        }

        boolean encrypted = properties.encryptionConfigured();
        String fileName = Dac7FileNaming.fileName(year, properties.declarantSiren().strip(), sequence, createdAt,
                encrypted);
        byte[] content = xml;
        if (encrypted) {
            try {
                content = Dac7FileEncryptor.gzipAndEncrypt(xml,
                        Dac7FileEncryptor.readEncryptionKey(Path.of(properties.publicKeyPath().strip())),
                        fileName.substring(0, fileName.length() - ".gpg".length()));
            } catch (Dac7FileEncryptor.KeyUnavailableException e) {
                log.warn("Export DAC7 {} : {}", year, e.getMessage());
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-export-key-unavailable",
                        "Encryption Key Unavailable",
                        "La clé publique de chiffrement de la DGFiP configurée est introuvable ou illisible.");
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("year", year);
        payload.put("mode", mode.name());
        payload.put("sellers", included.size());
        payload.put("excluded", excluded);
        payload.put("encrypted", encrypted);
        audit.log("DAC7_EXPORT", UUID.randomUUID(), "DAC7_EXPORT_GENERATED", adminId, payload);
        return new ExportFile(fileName, content, encrypted, included.size(), excluded);
    }

    /** CSV de contrôle (séparateur « ; », UTF-8 avec BOM pour les tableurs). Jamais le NIF : seulement oui/non. */
    public String controlCsv(Integer yearValue, UUID adminId) {
        int year = declarations.requireYear(yearValue);
        Declaration declaration = declarations.declaration(year, false);
        StringBuilder csv = new StringBuilder("﻿");
        List<String> header = new ArrayList<>(List.of("userId", "voyageur", "pays", "residence", "nifFourni",
                "identite"));
        for (int q = 1; q <= 4; q++) {
            header.addAll(List.of("t" + q + "NetEur", "t" + q + "CommissionEur", "t" + q + "Operations"));
        }
        header.addAll(List.of("operations", "netEur", "commissionEur", "eurComplet", "commissionComplete",
                "exclusions"));
        line(csv, header);
        for (Seller s : declaration.sellers()) {
            List<String> row = new ArrayList<>(List.of(s.userId().toString(), s.displayName(),
                    nullToEmpty(s.residenceCountry()), Dac7ResidencyStatus.DECLARABLE.name(), yesNo(s.taxInfoProvided()),
                    s.identityStatus().name()));
            for (QuarterReport q : s.report().quarters()) {
                row.addAll(List.of(q.netEur().toPlainString(), q.commissionEur().toPlainString(),
                        String.valueOf(q.operations())));
            }
            row.addAll(List.of(String.valueOf(s.operations()), s.netEur().toPlainString(),
                    s.commissionEur().toPlainString(), yesNo(s.eurComplete()), yesNo(s.commissionComplete()),
                    String.join(",", s.exclusions())));
            line(csv, row);
        }
        for (Other o : declaration.others()) {
            List<String> row = new ArrayList<>(List.of(o.userId().toString(), o.displayName(),
                    nullToEmpty(o.residenceCountry()), o.residency().name(), yesNo(o.taxInfoProvided()),
                    o.identityStatus().name()));
            for (int i = 0; i < 12 + 5; i++) {
                row.add("");
            }
            row.add("NON_DECLARE");
            line(csv, row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("year", year);
        payload.put("travelers", declaration.sellers().size() + declaration.others().size());
        audit.log("DAC7_EXPORT", UUID.randomUUID(), "DAC7_CONTROL_CSV_GENERATED", adminId, payload);
        return csv.toString();
    }

    private Dac7XmlSeller xmlSeller(Seller seller, UserEntity user) {
        Dac7SellerIdentity id = seller.identity();
        String resCountry = Dac7DgfipRules.resCountryCode(seller.residenceCountry());
        String tinCountry = user.getTaxCountry() == null ? resCountry : user.getTaxCountry();
        Dac7Address a = id.address();
        Dac7XmlSeller.Address address = new Dac7XmlSeller.Address(Dac7DgfipRules.resCountryCode(a.countryCode()),
                a.street(), a.line2(), a.postCode(), a.city(), a.free());
        List<Dac7XmlSeller.Quarter> quarters = seller.report().quarters().stream()
                .map(q -> new Dac7XmlSeller.Quarter(Dac7DeclarationService.roundEuro(q.netEur()), q.operations(),
                        Dac7DeclarationService.roundEuro(q.commissionEur())))
                .toList();
        boolean entity = id.type() == Dac7SellerIdentity.SellerType.ENTITY;
        return new Dac7XmlSeller(entity, resCountry, user.getTaxIdentificationNumber(), tinCountry,
                user.getVatNumber(), id.firstName(), id.lastName(), id.birthDate(), id.businessName(),
                id.registrationNumber(), address, quarters);
    }

    private Dac7XmlDocument.Operator operator() {
        return new Dac7XmlDocument.Operator(properties.declarantSiren().strip(), properties.operatorName().strip(),
                properties.businessNameOrDefault(), properties.operatorVat(), properties.operatorStreet(),
                properties.operatorPostCode(), properties.operatorCity().strip(), shortId());
    }

    private static Mode mode(String value) {
        try {
            return Mode.valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-export-invalid-request",
                    "Invalid Export Request", "Le mode doit être FULL ou PARTIAL.");
        }
    }

    static YadonyBusinessException blocked(List<String> blockers) {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "dac7-export-blocked", "Export Blocked",
                "L'export DAC7 est bloqué : " + String.join(", ", blockers) + ".",
                Map.of("blockers", List.copyOf(blockers)));
    }

    /** Partie unique d'une référence DGFiP (caractères autorisés par le motif du DocRefId). */
    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String yesNo(boolean value) {
        return value ? "oui" : "non";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void line(StringBuilder csv, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                csv.append(';');
            }
            csv.append(cell(values.get(i)));
        }
        csv.append("\r\n");
    }

    /** Guillemets si besoin ; une cellule qui commencerait par = + - @ est neutralisée (injection de formule). */
    static String cell(String raw) {
        String value = raw == null ? "" : raw;
        boolean number = value.matches("-?\\d+(\\.\\d+)?");
        if (!value.isEmpty() && !number && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            value = "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
