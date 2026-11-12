package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.Dac7Report.QuarterReport;
import com.yadony.api.dac7.Dac7Report.TravelerReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Déclaration DAC7 d'une année (lot 3b) : le calcul du lot 3a ({@link Dac7ReportService}), l'identité résolue sur
 * toutes les sources ({@link Dac7IdentityResolver}), les règles de la DGFiP ({@link Dac7DgfipRules}) et les blocages
 * de l'export. Base commune des pages admin, de l'export XML, du CSV de contrôle et des récapitulatifs.
 *
 * <p>Avant le calcul, et hors de sa transaction, les taux BCE de l'année sont téléchargés s'ils manquent
 * ({@link EcbExchangeRateProvider#ensureCoverage}) : aucun appel réseau pendant le calcul.
 */
@Service
public class Dac7DeclarationService {

    static final String DELETED_DISPLAY_NAME = "Compte supprimé";

    /** Raisons d'exclusion d'un vendeur du fichier (et blocages de l'export complet). */
    public static final String IDENTITY_INCOMPLETE = "IDENTITY_INCOMPLETE";
    public static final String TAX_INFO_MISSING = "TAX_INFO_MISSING";
    public static final String EUR_INCOMPLETE = "EUR_INCOMPLETE";
    public static final String INVALID_PARTNER_JURISDICTION = "INVALID_PARTNER_JURISDICTION";
    public static final String NO_SELLERS = "NO_SELLERS";
    /** Ajout au contrat : SIREN, raison sociale ou ville du siège de Yadony absents de la configuration. */
    public static final String OPERATOR_CONFIG_MISSING = "OPERATOR_CONFIG_MISSING";
    /** Tour 1 : prestataire de vérification injoignable et profil insuffisant ; réessayable. */
    public static final String IDENTITY_UNAVAILABLE = "IDENTITY_UNAVAILABLE";
    /**
     * Relecture n° 3, finding 6 : pages admin et CSV seulement. Identité pas encore lue (voir
     * {@link Dac7IdentityStatus#NOT_CHECKED}) ; jamais produit par l'export, qui relit chaque pièce.
     */
    public static final String IDENTITY_NOT_CHECKED = "IDENTITY_NOT_CHECKED";
    /** Tour 1 : partage admin décidé mais transfert non terminé : le montant déclaré serait sous-évalué. */
    public static final String PENDING_SPLITS = "PENDING_SPLITS";
    /** Exclusion seulement : État de résidence non transmis par la DGFiP cette année (annexe 6.1). */
    public static final String RESIDENCE_NOT_TRANSMITTED = "RESIDENCE_NOT_TRANSMITTED";
    /** Exclusion seulement : contrepartie nulle une fois arrondie à l'euro (contrôle de second niveau n° 2). */
    public static final String ZERO_CONSIDERATION = "ZERO_CONSIDERATION";
    /**
     * Exclusion seulement (export FULL comme PARTIAL, relecture n° 3, finding 2) : déjà retenu dans un fichier DÉPOSÉ
     * de cette année. Jamais réémis en déclaration initiale (DPI401/OECD1) : ce serait un doublon à la DGFiP ; une
     * rectification de montants déjà déposés est hors lot.
     */
    public static final String ALREADY_DEPOSITED = "ALREADY_DEPOSITED";
    /** Exclusion seulement : ses données ne passent pas le XSD de la DGFiP (valeur jamais rapportée). */
    public static final String XSD_INVALID = "XSD_INVALID";

    /**
     * Profondeur de la lecture d'identité : aucune (listes qui n'ont besoin que des noms : profil seul), verdict en
     * cache (pages admin), ou relecture en direct de la pièce (export, récapitulatifs).
     */
    public enum IdentityMode { NONE, CACHED, FRESH }

    /**
     * Un vendeur déclarable de l'année.
     *
     * @param identity     identité complète, seulement quand elle a été relue ({@code freshIdentity}), sinon nul
     * @param exclusions   raisons qui l'écartent du fichier, vide s'il part
     * @param currency     devise du montant déclaré : celle des paiements sans conversion, sinon l'euro
     * @param declaredNet  montant déclaré de l'année dans cette devise (net des remboursements déduits, frais retenus 0)
     * @param declaredComplete faux si une conversion en euros manque : le montant déclaré est alors partiel
     */
    public record Seller(UUID userId, String displayName, String residenceCountry, boolean taxInfoProvided,
                         Dac7IdentityStatus identityStatus, Dac7SellerIdentity identity, int remindersSent,
                         Instant lastDiligenceAt, TravelerReport report, int operations, BigDecimal netEur,
                         BigDecimal commissionEur, boolean eurComplete, boolean commissionComplete,
                         List<String> exclusions, boolean alreadyExported, String currency, BigDecimal declaredNet,
                         boolean declaredComplete) {

        /** Compatibilité : montants en euros, déclarés tels quels. */
        public Seller(UUID userId, String displayName, String residenceCountry, boolean taxInfoProvided,
                      Dac7IdentityStatus identityStatus, Dac7SellerIdentity identity, int remindersSent,
                      Instant lastDiligenceAt, TravelerReport report, int operations, BigDecimal netEur,
                      BigDecimal commissionEur, boolean eurComplete, boolean commissionComplete,
                      List<String> exclusions, boolean alreadyExported) {
            this(userId, displayName, residenceCountry, taxInfoProvided, identityStatus, identity, remindersSent,
                    lastDiligenceAt, report, operations, netEur, commissionEur, eurComplete, commissionComplete,
                    exclusions, alreadyExported, "EUR", netEur, eurComplete);
        }
    }

    /** Voyageur payé dans l'année mais non déclaré (résidence inconnue ou hors périmètre). */
    public record Other(UUID userId, String displayName, String residenceCountry, Dac7ResidencyStatus residency,
                        boolean taxInfoProvided, Dac7IdentityStatus identityStatus, int remindersSent,
                        Instant lastDiligenceAt) {
    }

    /**
     * Voyageur payé dont les informations fiscales manquent (statut de collecte à compléter) : jamais exporté en
     * silence, listé à part avec la raison (rapport d'anomalies).
     */
    public record CollectionAnomaly(UUID userId, String displayName, List<String> reasons) {
    }

    /**
     * @param warnings remboursements à réconcilier + anomalies de l'année : jamais bloquants, montrés avant l'export
     *                 (les remboursements repris au voyageur la même année sont déjà déduits du montant déclaré)
     * @param collectionAnomalies voyageurs déclarables payés dont le numéro fiscal ou l'identité manquent
     */
    public record Declaration(int year, Dac7Report report, List<Seller> sellers, List<Other> others,
                              List<String> invalidPartnerJurisdictions, List<String> blockers,
                              Map<UUID, String> displayNames, int warnings,
                              List<CollectionAnomaly> collectionAnomalies) {

        public Declaration(int year, Dac7Report report, List<Seller> sellers, List<Other> others,
                           List<String> invalidPartnerJurisdictions, List<String> blockers,
                           Map<UUID, String> displayNames, int warnings) {
            this(year, report, sellers, others, invalidPartnerJurisdictions, blockers, displayNames, warnings,
                    List.of());
        }
    }

    private final Dac7ReportService reports;
    private final EcbExchangeRateProvider rates;
    private final Dac7IdentityResolver identities;
    private final UserRepository users;
    private final Dac7AdminReadModel adminReads;
    private final Dac7ResidencyPolicy residency;
    private final Dac7Properties properties;
    private final Dac7ExportProperties exportProperties;
    private final Clock clock;

    @Autowired
    public Dac7DeclarationService(Dac7ReportService reports, EcbExchangeRateProvider rates,
                                  Dac7IdentityResolver identities, UserRepository users, Dac7AdminReadModel adminReads,
                                  Dac7ResidencyPolicy residency, Dac7Properties properties,
                                  Dac7ExportProperties exportProperties) {
        this(reports, rates, identities, users, adminReads, residency, properties, exportProperties,
                Clock.systemUTC());
    }

    Dac7DeclarationService(Dac7ReportService reports, EcbExchangeRateProvider rates, Dac7IdentityResolver identities,
                           UserRepository users, Dac7AdminReadModel adminReads, Dac7ResidencyPolicy residency,
                           Dac7Properties properties, Dac7ExportProperties exportProperties, Clock clock) {
        this.reports = reports;
        this.rates = rates;
        this.identities = identities;
        this.users = users;
        this.adminReads = adminReads;
        this.residency = residency;
        this.properties = properties;
        this.exportProperties = exportProperties;
        this.clock = clock;
    }

    /** Année civile déclarable : de 2023 (premier millésime DAC7) à l'année en cours. */
    public int requireYear(Integer year) {
        int current = clock.instant().atZone(ZoneOffset.UTC).getYear();
        if (year == null || year < 2023 || year > current) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-year-invalid",
                    "Invalid Year", "L'année doit être comprise entre 2023 et " + current + ".");
        }
        return year;
    }

    /**
     * Année close : avant le 1er janvier suivant, les montants de l'année sont forcément partiels (récapitulatifs :
     * 422 {@code dac7-year-not-closed}).
     */
    public int requireClosedYear(Integer year) {
        int checked = requireYear(year);
        if (checked >= clock.instant().atZone(ZoneOffset.UTC).getYear()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-year-not-closed",
                    "Year Not Closed", "L'année " + checked + " n'est pas terminée : les récapitulatifs ne peuvent "
                    + "être envoyés qu'à partir du 1er janvier " + (checked + 1) + ".");
        }
        return checked;
    }

    /** Compatibilité : {@code true} = identité relue en direct, {@code false} = verdict en cache. */
    public Declaration declaration(int year, boolean freshIdentity) {
        return declaration(year, freshIdentity ? IdentityMode.FRESH : IdentityMode.CACHED);
    }

    public Declaration declaration(int year, IdentityMode mode) {
        rates.ensureCoverage(year);
        Dac7Report report = reports.annualReport(year);

        Set<UUID> ids = new LinkedHashSet<>();
        report.travelers().forEach(t -> ids.add(t.travelerId()));
        report.identityReviewTravelers().forEach(t -> ids.add(t.travelerId()));
        ids.addAll(report.unknownResidenceTravelers());
        ids.addAll(report.nonDeclarableTravelers());
        report.refundsToReconcile().forEach(r -> ids.add(r.travelerId()));
        report.anomalies().stream().map(Dac7Report.Anomaly::travelerId).filter(java.util.Objects::nonNull)
                .forEach(ids::add);
        Map<UUID, UserEntity> byId = users.findAllById(ids).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        Map<UUID, Instant> lastDiligence = ids.isEmpty() ? Map.of() : adminReads.lastDiligence(ids);
        Map<UUID, String> names = new HashMap<>();
        ids.forEach(id -> names.put(id, displayName(byId.get(id))));

        List<String> invalidPartners = invalidPartners(year, report);
        List<TravelerReport> declarable = new ArrayList<>(report.travelers());
        declarable.addAll(report.identityReviewTravelers());
        declarable.sort(java.util.Comparator.comparing(TravelerReport::travelerId));
        Map<UUID, Dac7IdentityStatus> cached = mode == IdentityMode.CACHED
                ? identities.statuses(declarable.stream().map(t -> byId.get(t.travelerId()))
                        .filter(java.util.Objects::nonNull).toList())
                : Map.of();
        Set<UUID> pendingSplit = new java.util.HashSet<>();
        report.pendingSplits().forEach(p -> pendingSplit.add(p.travelerId()));
        Set<UUID> exported = adminReads.exportedTravelers(year);
        List<Seller> sellers = new ArrayList<>();
        for (TravelerReport traveler : declarable) {
            sellers.add(seller(year, traveler, byId.get(traveler.travelerId()), names,
                    lastDiligence.get(traveler.travelerId()), mode, cached, pendingSplit, exported));
        }

        List<Other> others = new ArrayList<>();
        for (UUID id : report.unknownResidenceTravelers()) {
            others.add(other(id, byId.get(id), Dac7ResidencyStatus.RESIDENCE_INCONNUE, names, lastDiligence));
        }
        for (UUID id : report.nonDeclarableTravelers()) {
            others.add(other(id, byId.get(id), Dac7ResidencyStatus.NON_DECLARABLE, names, lastDiligence));
        }
        List<CollectionAnomaly> collectionAnomalies = sellers.stream()
                .filter(s -> s.exclusions().contains(TAX_INFO_MISSING) || s.exclusions().contains(IDENTITY_INCOMPLETE))
                .map(s -> new CollectionAnomaly(s.userId(), s.displayName(), s.exclusions().stream()
                        .filter(r -> TAX_INFO_MISSING.equals(r) || IDENTITY_INCOMPLETE.equals(r)).toList()))
                .toList();
        return new Declaration(year, report, sellers, others, invalidPartners,
                blockers(sellers, invalidPartners, exportProperties.operatorConfigured()), names,
                report.refundsToReconcile().size() + report.anomalies().size(), collectionAnomalies);
    }

    private Seller seller(int year, TravelerReport traveler, UserEntity user, Map<UUID, String> names,
                          Instant lastDiligence, IdentityMode mode, Map<UUID, Dac7IdentityStatus> cached,
                          Set<UUID> pendingSplit, Set<UUID> exported) {
        UUID id = traveler.travelerId();
        Dac7SellerIdentity identity = null;
        Dac7IdentityStatus status;
        if (user == null) {
            identity = identities.resolveMissing(id);
            status = identity.status();
        } else if (mode == IdentityMode.FRESH) {
            identity = identities.resolve(user);
            status = identity.status();
        } else if (mode == IdentityMode.CACHED) {
            status = cached.getOrDefault(id, Dac7IdentityStatus.NOT_CHECKED);
        } else {
            status = identities.profileStatus(user);
        }
        int operations = 0;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal declaredNet = BigDecimal.ZERO;
        long roundedConsideration = 0;
        boolean eur = true;
        boolean declaredComplete = true;
        boolean commissionComplete = true;
        for (QuarterReport q : traveler.quarters()) {
            operations += q.operations();
            net = net.add(q.netEur());
            commission = commission.add(q.commissionEur());
            declaredNet = declaredNet.add(q.net());
            roundedConsideration += roundAmount(q.net());
            eur &= q.eurComplete();
            declaredComplete &= q.declaredComplete();
            commissionComplete &= q.commissionComplete();
        }
        List<String> exclusions = new ArrayList<>();
        if (status == Dac7IdentityStatus.UNAVAILABLE) exclusions.add(IDENTITY_UNAVAILABLE);
        else if (status == Dac7IdentityStatus.NOT_CHECKED) exclusions.add(IDENTITY_NOT_CHECKED);
        else if (status != Dac7IdentityStatus.COMPLETE) exclusions.add(IDENTITY_INCOMPLETE);
        if (!traveler.taxInfoProvided()) exclusions.add(TAX_INFO_MISSING);
        // Montant déclaré partiel : une conversion en euros manque. Une commission introuvable ne bloque plus : aucun
        // frais n'est retenu sur le voyageur, elle ne part dans aucun montant déclaré.
        if (!declaredComplete) exclusions.add(EUR_INCOMPLETE);
        if (!Dac7DgfipRules.transmitted(year, traveler.residenceCountry())) exclusions.add(RESIDENCE_NOT_TRANSMITTED);
        if (declaredComplete && roundedConsideration <= 0) exclusions.add(ZERO_CONSIDERATION);
        if (pendingSplit.contains(id)) exclusions.add(PENDING_SPLITS);
        return new Seller(id, names.get(id), traveler.residenceCountry(), traveler.taxInfoProvided(), status, identity,
                user == null ? 0 : user.getTaxReminderCount(), lastDiligence, traveler, operations,
                net.setScale(2, RoundingMode.HALF_UP), commission.setScale(2, RoundingMode.HALF_UP), eur,
                commissionComplete, List.copyOf(exclusions), exported.contains(id), traveler.currency(),
                declaredNet.setScale(2, RoundingMode.HALF_UP), declaredComplete);
    }

    private Other other(UUID id, UserEntity user, Dac7ResidencyStatus status, Map<UUID, String> names,
                        Map<UUID, Instant> lastDiligence) {
        return new Other(id, names.get(id), user == null ? null : Dac7ResidencyPolicy.residenceCountryOf(user), status,
                user != null && user.getTaxIdentificationNumber() != null,
                user == null ? Dac7IdentityStatus.INCOMPLETE : identities.profileStatus(user),
                user == null ? 0 : user.getTaxReminderCount(), lastDiligence.get(id));
    }

    /**
     * Codes partenaires invalides du calcul, plus les partenaires configurés que la DGFiP ne transmet pas cette année
     * (annexe 6.1 : aucun pour 2026). Un résident de ces États serait rejeté par le contrôle de second niveau n° 8.
     */
    private List<String> invalidPartners(int year, Dac7Report report) {
        Set<String> invalid = new TreeSet<>(report.invalidPartnerJurisdictions());
        for (String raw : properties.partnerJurisdictionsOrEmpty()) {
            String code = Dac7ResidencyPolicy.normalize(raw);
            if (code != null && !Dac7DgfipRules.transmitted(year, code)) {
                invalid.add(code);
            }
        }
        return List.copyOf(invalid);
    }

    /**
     * Blocages de l'export complet pour ces vendeurs, lus sur leurs exclusions (même règle partout : vue d'ensemble,
     * et export sur les seuls vendeurs pas encore déposés).
     */
    static List<String> blockers(List<Seller> sellers, List<String> invalidPartners, boolean operatorConfigured) {
        List<String> blockers = new ArrayList<>();
        if (sellers.stream().anyMatch(s -> s.exclusions().contains(IDENTITY_INCOMPLETE))) {
            blockers.add(IDENTITY_INCOMPLETE);
        }
        if (sellers.stream().anyMatch(s -> s.exclusions().contains(IDENTITY_UNAVAILABLE))) {
            blockers.add(IDENTITY_UNAVAILABLE);
        }
        if (sellers.stream().anyMatch(s -> s.exclusions().contains(IDENTITY_NOT_CHECKED))) {
            blockers.add(IDENTITY_NOT_CHECKED);
        }
        for (String code : List.of(TAX_INFO_MISSING, EUR_INCOMPLETE, PENDING_SPLITS)) {
            if (sellers.stream().anyMatch(s -> s.exclusions().contains(code))) blockers.add(code);
        }
        if (!invalidPartners.isEmpty()) blockers.add(INVALID_PARTNER_JURISDICTION);
        if (sellers.isEmpty()) blockers.add(NO_SELLERS);
        if (!operatorConfigured) blockers.add(OPERATOR_CONFIG_MISSING);
        return List.copyOf(blockers);
    }

    /**
     * Montant entier, la fraction de 0,50 ou plus comptée pour 1. Le XSD DGFiP type les montants {@code xsd:integer}
     * avec un code devise : l'arrondi vaut pour toute devise (zones ConsQ et FeesQ).
     */
    public static long roundAmount(BigDecimal amount) {
        return amount == null ? 0 : amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Euros entiers (compatibilité) : même arrondi que {@link #roundAmount}. */
    public static long roundEuro(BigDecimal eur) {
        return roundAmount(eur);
    }

    static String displayName(UserEntity user) {
        return user == null ? DELETED_DISPLAY_NAME : user.adminDisplayName();
    }

    public boolean collectionEnabled() {
        return properties.enabledOrDefault();
    }

    Dac7ResidencyPolicy residency() {
        return residency;
    }
}
