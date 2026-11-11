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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.bouncycastle.openpgp.PGPPublicKey;

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

    /** @param warnings remboursements à réconcilier + anomalies de l'année (non bloquants, en-tête X-Dac7-Warnings) */
    public record ExportFile(String fileName, byte[] content, boolean encrypted, int sellers, int excluded,
                             int warnings) {
    }

    private final Dac7DeclarationService declarations;
    private final UserRepository users;
    private final Dac7ExportProperties properties;
    private final AuditService audit;
    private final Dac7AdminReadModel reads;
    private final TransactionOperations transactions;
    private final Clock clock;

    @Autowired
    public Dac7ExportService(Dac7DeclarationService declarations, UserRepository users,
                             Dac7ExportProperties properties, AuditService audit, Dac7AdminReadModel reads,
                             PlatformTransactionManager transactionManager) {
        this(declarations, users, properties, audit, reads, new TransactionTemplate(transactionManager),
                Clock.systemUTC());
    }

    Dac7ExportService(Dac7DeclarationService declarations, UserRepository users, Dac7ExportProperties properties,
                      AuditService audit, Dac7AdminReadModel reads, TransactionOperations transactions, Clock clock) {
        this.declarations = declarations;
        this.users = users;
        this.properties = properties;
        this.audit = audit;
        this.reads = reads;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Compatibilité : sans ré-export des voyageurs déjà retenus. */
    public ExportFile export(Integer yearValue, String modeValue, Integer sequenceValue, UUID adminId) {
        return export(yearValue, modeValue, sequenceValue, false, adminId);
    }

    /**
     * @param includeAlreadyExported PARTIAL : reprendre aussi les voyageurs déjà retenus dans un fichier généré pour
     *                               cette année (faux par défaut : ils sont écartés, raison {@code ALREADY_EXPORTED})
     */
    public ExportFile export(Integer yearValue, String modeValue, Integer sequenceValue,
                             boolean includeAlreadyExported, UUID adminId) {
        int year = declarations.requireYear(yearValue);
        Mode mode = mode(modeValue);
        int sequence = sequenceValue == null ? 0 : sequenceValue;
        if (sequence < 0 || sequence > 999) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-export-invalid-request",
                    "Invalid Export Request", "Le numéro d'ordre doit être compris entre 0 et 999.");
        }
        // Configuration d'abord : rien ne sert de calculer (taux BCE, lectures Didit) pour un fichier impossible.
        if (!properties.operatorConfigured()) {
            throw blocked(List.of(Dac7DeclarationService.OPERATOR_CONFIG_MISSING), List.of());
        }
        boolean encrypted = properties.encryptionConfigured();
        if (!encrypted && !properties.unencryptedAllowed()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "dac7-unencrypted-not-allowed",
                    "Unencrypted Export Not Allowed", "Aucune clé publique de la DGFiP n'est configurée et l'export en "
                    + "clair n'est pas autorisé.");
        }
        PGPPublicKey key = encrypted ? key() : null;

        Declaration declaration = declarations.declaration(year, Dac7DeclarationService.IdentityMode.FRESH);
        if (mode == Mode.FULL && !declaration.blockers().isEmpty()) {
            throw blocked(declaration.blockers(), declaration.sellers().stream()
                    .filter(s -> !s.exclusions().isEmpty()).map(Seller::userId).toList());
        }
        String siren = properties.declarantSiren().strip();
        LocalDateTime createdAt = LocalDateTime.now(clock.withZone(PARIS)).withNano(0);
        String unique = createdAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "_"
                + shortId();
        Dac7XmlDocument.Operator operator = operator(siren, sequence);

        Map<UUID, UserEntity> byId = users.findAllById(declaration.sellers().stream()
                        .filter(s -> s.exclusions().isEmpty()).map(Seller::userId).toList()).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        List<Dac7XmlDocument.Seller> sellers = new ArrayList<>();
        List<Dac7AdminReadModel.ExportSellerRow> rows = new ArrayList<>();
        List<UUID> invalid = new ArrayList<>();
        for (Seller seller : declaration.sellers()) {
            List<String> reasons = new ArrayList<>(seller.exclusions());
            if (reasons.isEmpty() && mode == Mode.PARTIAL && seller.alreadyExported() && !includeAlreadyExported) {
                reasons.add(Dac7DeclarationService.ALREADY_EXPORTED);
            }
            UserEntity user = byId.get(seller.userId());
            if (reasons.isEmpty() && (user == null || seller.identity() == null)) {
                // Compte supprimé entre le calcul et la lecture : jamais déclaré à moitié.
                reasons.add(Dac7DeclarationService.IDENTITY_INCOMPLETE);
            }
            if (!reasons.isEmpty()) {
                rows.add(new Dac7AdminReadModel.ExportSellerRow(seller.userId(), false, null,
                        String.join(",", reasons)));
                continue;
            }
            Dac7XmlDocument.Seller entry = new Dac7XmlDocument.Seller(xmlSeller(seller, user),
                    docRefUniquePart(seller.userId(), sequence));
            if (!validAlone(year, createdAt, unique, operator, entry)) {
                invalid.add(seller.userId());
                rows.add(new Dac7AdminReadModel.ExportSellerRow(seller.userId(), false, null,
                        Dac7DeclarationService.XSD_INVALID));
                continue;
            }
            sellers.add(entry);
            rows.add(new Dac7AdminReadModel.ExportSellerRow(seller.userId(), true,
                    Dac7XmlWriter.reference(year, siren, entry.docRefUniquePart()), null));
        }
        if (mode == Mode.FULL && !invalid.isEmpty()) {
            log.warn("Export DAC7 {} : {} voyageur(s) non conformes au schéma DGFiP", year, invalid.size());
            throw blocked(List.of(Dac7DeclarationService.XSD_INVALID), invalid);
        }
        if (sellers.isEmpty()) {
            List<String> blockers = new ArrayList<>(declaration.blockers());
            if (!blockers.contains(Dac7DeclarationService.NO_SELLERS)) {
                blockers.add(Dac7DeclarationService.NO_SELLERS);
            }
            throw blocked(blockers, rows.stream().map(Dac7AdminReadModel.ExportSellerRow::userId).toList());
        }

        byte[] xml = Dac7XmlWriter.write(new Dac7XmlDocument(year, createdAt, unique, operator, sellers));
        try {
            Dac7XsdValidator.validate(xml);
        } catch (Dac7XsdValidator.InvalidFileException e) {
            // Chaque vendeur a passé seul : l'erreur ne peut venir que de l'assemblage. Position seulement.
            log.warn("Export DAC7 {} non conforme au schéma DGFiP (ligne {}, colonne {})", year, e.line(), e.column());
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-export-invalid",
                    "Invalid Export", "Le fichier généré n'est pas conforme au schéma de la DGFiP.");
        }
        String fileName = Dac7FileNaming.fileName(year, siren, sequence, createdAt, encrypted);
        byte[] content = encrypted
                ? Dac7FileEncryptor.gzipAndEncrypt(xml, key, fileName.substring(0, fileName.length() - ".gpg".length()))
                : xml;

        int excluded = declaration.sellers().size() - sellers.size();
        UUID runId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("year", year);
        payload.put("mode", mode.name());
        payload.put("sequence", sequence);
        payload.put("sellers", sellers.size());
        payload.put("excluded", excluded);
        payload.put("encrypted", encrypted);
        payload.put("warnings", declaration.warnings());
        transactions.executeWithoutResult(status -> {
            reads.recordExport(year, runId, sequence, mode.name(), adminId, clock.instant(), rows);
            audit.log("DAC7_EXPORT", runId, "DAC7_EXPORT_GENERATED", adminId, payload);
        });
        return new ExportFile(fileName, content, encrypted, sellers.size(), excluded, declaration.warnings());
    }

    private PGPPublicKey key() {
        try {
            return Dac7FileEncryptor.readEncryptionKey(Path.of(properties.publicKeyPath().strip()), clock.instant());
        } catch (Dac7FileEncryptor.KeyUnavailableException e) {
            log.warn("Export DAC7 : {}", e.getMessage());
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-export-key-unavailable",
                    "Encryption Key Unavailable",
                    "La clé publique de chiffrement de la DGFiP configurée est introuvable, illisible ou sans "
                    + "sous-clé de chiffrement valide.");
        }
    }

    /** Le vendeur passe-t-il seul le XSD (document minimal : opérateur + lui) ? Aucune valeur rapportée. */
    private static boolean validAlone(int year, LocalDateTime createdAt, String unique,
                                      Dac7XmlDocument.Operator operator, Dac7XmlDocument.Seller seller) {
        try {
            Dac7XsdValidator.validate(Dac7XmlWriter.write(new Dac7XmlDocument(year, createdAt, unique, operator,
                    List.of(seller))));
            return true;
        } catch (Dac7XsdValidator.InvalidFileException | IllegalStateException e) {
            return false;
        }
    }

    /**
     * Partie unique du DocRefId, DÉTERMINISTE (recalculable pour une rectificative) : identifiant interne du voyageur
     * (32 caractères hexadécimaux, aucune donnée personnelle) et numéro d'ordre de la déclaration. Avec
     * {@code OP_<année>_<SIREN>_}, la référence ne se répète jamais d'un fichier déposé à l'autre tant que l'ordre
     * change (contrôle de second niveau 14-2).
     */
    static String docRefUniquePart(UUID userId, int sequence) {
        return userId.toString().replace("-", "") + "_" + String.format("%03d", sequence);
    }

    /** CSV de contrôle (séparateur « ; », UTF-8 avec BOM pour les tableurs). Jamais le NIF : seulement oui/non. */
    public String controlCsv(Integer yearValue, UUID adminId) {
        int year = declarations.requireYear(yearValue);
        Declaration declaration = declarations.declaration(year, Dac7DeclarationService.IdentityMode.CACHED);
        StringBuilder csv = new StringBuilder("﻿");
        List<String> header = new ArrayList<>(List.of("userId", "voyageur", "pays", "residence", "nifFourni",
                "identite"));
        for (int q = 1; q <= 4; q++) {
            header.addAll(List.of("t" + q + "NetEur", "t" + q + "CommissionEur", "t" + q + "Operations"));
        }
        header.addAll(List.of("operations", "netEur", "commissionEur", "eurComplet", "commissionComplete",
                "dejaExporte", "exclusions"));
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
                    yesNo(s.alreadyExported()), String.join(",", s.exclusions())));
            line(csv, row);
        }
        for (Other o : declaration.others()) {
            List<String> row = new ArrayList<>(List.of(o.userId().toString(), o.displayName(),
                    nullToEmpty(o.residenceCountry()), o.residency().name(), yesNo(o.taxInfoProvided()),
                    o.identityStatus().name()));
            for (int i = 0; i < 12 + 6; i++) {
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
        String country = Dac7DgfipRules.resCountryCode(a.countryCode());
        // Un complément (« chez M. X », « BP 12 », « Bât. B ») n'a pas de zone sûre dans AddressFix : l'adresse part
        // alors entière dans la zone libre du XSD (AddressFree).
        Dac7XmlSeller.Address address = a.line2() != null && !a.line2().isBlank() && a.detailed()
                ? new Dac7XmlSeller.Address(country, null, null, null, null, freeLine(a))
                : new Dac7XmlSeller.Address(country, a.street(), null, a.postCode(), a.city(), a.free());
        List<Dac7XmlSeller.Quarter> quarters = seller.report().quarters().stream()
                .map(q -> new Dac7XmlSeller.Quarter(Dac7DeclarationService.roundEuro(q.netEur()), q.operations(),
                        Dac7DeclarationService.roundEuro(q.commissionEur())))
                .toList();
        boolean entity = id.type() == Dac7SellerIdentity.SellerType.ENTITY;
        return new Dac7XmlSeller(entity, resCountry, user.getTaxIdentificationNumber(), tinCountry,
                user.getVatNumber(), id.firstName(), id.lastName(), id.birthDate(), id.businessName(),
                id.registrationNumber(), address, quarters);
    }

    private static String freeLine(Dac7Address a) {
        StringBuilder line = new StringBuilder();
        for (String part : new String[]{a.street(), a.line2()}) {
            if (part != null && !part.isBlank()) {
                line.append(line.length() == 0 ? "" : ", ").append(part.strip());
            }
        }
        String town = ((a.postCode() == null ? "" : a.postCode().strip() + " ") + a.city().strip()).strip();
        return line.append(line.length() == 0 ? "" : ", ").append(town).toString();
    }

    private Dac7XmlDocument.Operator operator(String siren, int sequence) {
        return new Dac7XmlDocument.Operator(siren, properties.operatorName().strip(),
                properties.businessNameOrDefault(), properties.operatorVat(), properties.operatorStreet(),
                properties.operatorPostCode(), properties.operatorCity().strip(),
                "OP" + String.format("%03d", sequence));
    }

    private static Mode mode(String value) {
        try {
            return Mode.valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-export-invalid-request",
                    "Invalid Export Request", "Le mode doit être FULL ou PARTIAL.");
        }
    }

    /** 409 avec les codes et les identifiants internes des voyageurs en cause (jamais une valeur). */
    static YadonyBusinessException blocked(List<String> blockers, List<UUID> travelerIds) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("blockers", List.copyOf(blockers));
        properties.put("travelerIds", travelerIds.stream().map(UUID::toString).toList());
        return new YadonyBusinessException(HttpStatus.CONFLICT, "dac7-export-blocked", "Export Blocked",
                "L'export DAC7 est bloqué : " + String.join(", ", blockers) + ".", properties);
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
