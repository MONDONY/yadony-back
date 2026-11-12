package com.yadony.api.dac7;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.Dac7DeclarationService.Declaration;
import com.yadony.api.dac7.Dac7DeclarationService.Other;
import com.yadony.api.dac7.Dac7DeclarationService.Seller;
import com.yadony.api.dac7.dto.Dac7AnomalyRow;
import com.yadony.api.dac7.dto.Dac7DiligenceEntry;
import com.yadony.api.dac7.dto.Dac7OverviewResponse;
import com.yadony.api.dac7.dto.Dac7RefundRow;
import com.yadony.api.dac7.dto.Dac7SellerRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pages admin DAC7 (contrat 2, lot 3c) : vue d'ensemble, voyageurs, registre, relance, décision de l'équipe,
 * remboursements et anomalies. Aucune réponse ne porte le NIF, la TVA, la date de naissance ni l'adresse. La lecture
 * n'est pas bloquée par l'interrupteur de collecte (l'équipe prépare à blanc) ; la relance, qui écrit au voyageur,
 * l'est (409 {@code dac7-collection-disabled}).
 */
@Service
public class Dac7AdminService {

    public enum SellerFilter { ALL, MISSING_TAX_INFO, IDENTITY_REVIEW, UNKNOWN_RESIDENCE, NON_DECLARABLE }

    private final Dac7DeclarationService declarations;
    private final Dac7AdminReadModel reads;
    private final Dac7ReminderService reminders;
    private final Dac7DueDiligenceService diligence;
    private final Dac7ReminderProperties reminderProperties;
    private final AuditService audit;
    private final Clock clock;

    @Autowired
    public Dac7AdminService(Dac7DeclarationService declarations, Dac7AdminReadModel reads,
                            Dac7ReminderService reminders, Dac7DueDiligenceService diligence,
                            Dac7ReminderProperties reminderProperties, AuditService audit) {
        this(declarations, reads, reminders, diligence, reminderProperties, audit, Clock.systemUTC());
    }

    Dac7AdminService(Dac7DeclarationService declarations, Dac7AdminReadModel reads, Dac7ReminderService reminders,
                     Dac7DueDiligenceService diligence, Dac7ReminderProperties reminderProperties,
                     AuditService audit, Clock clock) {
        this.declarations = declarations;
        this.reads = reads;
        this.reminders = reminders;
        this.diligence = diligence;
        this.reminderProperties = reminderProperties;
        this.audit = audit;
        this.clock = clock;
    }

    public Dac7OverviewResponse overview(Integer yearValue) {
        Declaration d = declarations.declaration(declarations.requireYear(yearValue), false);
        List<Seller> sellers = d.sellers();
        int operations = sellers.stream().mapToInt(Seller::operations).sum();
        BigDecimal net = sellers.stream().map(Seller::netEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = sellers.stream().map(Seller::commissionEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        int withTax = (int) sellers.stream().filter(Seller::taxInfoProvided).count();
        int identityReview = (int) sellers.stream()
                .filter(s -> s.identityStatus() != Dac7IdentityStatus.COMPLETE).count();
        return new Dac7OverviewResponse(d.year(), declarations.collectionEnabled(),
                reminderProperties.enabled() && declarations.collectionEnabled(), sellers.size(), withTax,
                sellers.size() - withTax, identityReview, d.report().unknownResidenceTravelers().size(),
                d.report().nonDeclarableTravelers().size(), d.report().refundsToReconcile().size(),
                d.report().pendingSplits().size(), d.report().anomalies().size(), d.invalidPartnerJurisdictions(),
                new Dac7OverviewResponse.Totals(operations, net, commission),
                sellers.stream().allMatch(Seller::eurComplete), sellers.stream().allMatch(Seller::commissionComplete),
                d.blockers().isEmpty(), d.blockers(), d.warnings());
    }

    public List<Dac7SellerRow> sellers(Integer yearValue, String filterValue) {
        SellerFilter filter = filter(filterValue);
        Declaration d = declarations.declaration(declarations.requireYear(yearValue), false);
        List<Dac7SellerRow> rows = new ArrayList<>();
        for (Seller s : d.sellers()) {
            boolean keep = switch (filter) {
                case ALL -> true;
                case MISSING_TAX_INFO -> !s.taxInfoProvided();
                case IDENTITY_REVIEW -> s.identityStatus() != Dac7IdentityStatus.COMPLETE;
                default -> false;
            };
            if (keep) {
                // Résident d'un État partenaire non transmis cette année : jamais sollicité (règle de sollicitation).
                Dac7SolicitationStatus solicitation = s.exclusions().contains(
                        Dac7DeclarationService.RESIDENCE_NOT_TRANSMITTED) && !s.taxInfoProvided()
                        ? Dac7SolicitationStatus.NE_PAS_SOLLICITER
                        : Dac7SolicitationPolicy.decide(Dac7ResidencyStatus.DECLARABLE, true, s.taxInfoProvided());
                rows.add(new Dac7SellerRow(s.userId(), s.displayName(), s.residenceCountry(),
                        Dac7ResidencyStatus.DECLARABLE.name(), s.taxInfoProvided(), s.identityStatus().name(),
                        solicitation.name(), s.remindersSent(), s.lastDiligenceAt(), s.operations(), s.netEur(),
                        s.commissionEur(), s.eurComplete(), s.commissionComplete(), s.alreadyExported()));
            }
        }
        for (Other o : d.others()) {
            boolean keep = filter == SellerFilter.ALL
                    || (filter == SellerFilter.UNKNOWN_RESIDENCE && o.residency() == Dac7ResidencyStatus.RESIDENCE_INCONNUE)
                    || (filter == SellerFilter.NON_DECLARABLE && o.residency() == Dac7ResidencyStatus.NON_DECLARABLE);
            if (keep) {
                rows.add(new Dac7SellerRow(o.userId(), o.displayName(), o.residenceCountry(), o.residency().name(),
                        o.taxInfoProvided(), o.identityStatus().name(),
                        Dac7SolicitationPolicy.decide(o.residency(), true, o.taxInfoProvided()).name(),
                        o.remindersSent(), o.lastDiligenceAt(), null, null, null, null, null, null));
            }
        }
        return rows;
    }

    public List<Dac7DiligenceEntry> diligence(UUID userId) {
        return reads.diligence(userId).stream()
                .map(r -> new Dac7DiligenceEntry(r.at(), r.action(), r.channel(), r.actorName(), r.note()))
                .toList();
    }

    public void remind(UUID userId, UUID adminId) {
        if (!declarations.collectionEnabled()) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "dac7-collection-disabled", "problem.dac7.dac7-collection-disabled");
        }
        Dac7ReminderService.Outcome outcome = reminders.remindByAdmin(userId, adminId, clock.instant());
        switch (outcome) {
            case SENT -> audit.log("USER", userId, "DAC7_REMINDER_SENT", adminId, Map.of("channel", "ADMIN"));
            case NOT_FOUND -> throw YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found");
            case TOO_SOON -> throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "dac7-remind-too-soon", "problem.dac7.dac7-remind-too-soon");
            case EXHAUSTED -> throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "dac7-reminders-exhausted", "problem.dac7.dac7-reminders-exhausted");
            default -> throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "dac7-not-solicitable", "problem.dac7.dac7-not-solicitable");
        }
    }

    public void decision(UUID userId, UUID adminId, String note) {
        String cleaned = note == null ? "" : note.strip();
        if (cleaned.isEmpty() || cleaned.length() > Dac7DueDiligenceService.MAX_NOTE_LENGTH) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-note-invalid", "problem.dac7.dac7-note-invalid", String.valueOf(Dac7DueDiligenceService.MAX_NOTE_LENGTH));
        }
        if (looksSensitive(cleaned)) {
            // Jamais recopiée : elle pourrait contenir un NIF ou un numéro de compte.
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-note-sensitive",
                    "Sensitive Note", NOTE_SENSITIVE_MESSAGE);
        }
        if (!reads.userExists(userId)) {
            throw YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found");
        }
        diligence.record(userId, Dac7DueDiligenceAction.TEAM_DECISION, Dac7DueDiligenceChannel.ADMIN, adminId, cleaned);
        // Le fait seulement : la note reste au registre, jamais dans l'audit.
        audit.log("USER", userId, "DAC7_TEAM_DECISION", adminId, Map.of("noteLength", cleaned.length()));
    }

    static final String NOTE_SENSITIVE_MESSAGE = "La note semble contenir un numéro ou un identifiant sensible "
            + "(9 chiffres consécutifs ou plus, un mot de 9 caractères ou plus mêlant lettres et chiffres, ou un IBAN "
            + "écrit par groupes de 4) : ni NIF, ni numéro de TVA, ni numéro de compte dans le registre. Un UUID ou un "
            + "identifiant Stripe (pi_…, acct_…) reste accepté.";

    /** UUID (identifiant interne, session Didit) : collé par l'équipe, jamais une donnée fiscale. */
    private static final java.util.regex.Pattern UUID_TOKEN = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![A-Za-z0-9])");
    /** Identifiant Stripe : préfixe connu, tiret bas, puis un corps qui contient au moins une lettre. */
    private static final java.util.regex.Pattern STRIPE_TOKEN = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9_])(?:acct|ba|card|ch|cs|cus|evt|fee|ich|in|ipi|pi|pm|po|pr|py|pyr|re|req|seti|si|src|sub"
            + "|tr|trr|txn)_(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{8,}(?![A-Za-z0-9_])");
    private static final java.util.regex.Pattern DIGITS_RUN = java.util.regex.Pattern.compile("\\d{9,}");
    /** IBAN écrit par groupes de 4 : 2 lettres et 2 chiffres, puis au moins deux groupes séparés par une espace. */
    private static final java.util.regex.Pattern IBAN_GROUPS = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9])[A-Za-z]{2}\\d{2}(?: [A-Za-z0-9]{4}){2,}");

    /**
     * Refusée (relecture n° 3, finding 7) si, une fois retirés les UUID et les identifiants Stripe, elle contient :
     * 9 chiffres CONSÉCUTIFS ou plus (NIF, numéro de compte) ; un jeton alphanumérique SANS tiret d'au moins 9
     * caractères qui contient des chiffres (TVA FR…, IBAN collé) ; un IBAN écrit par groupes de 4 avec des espaces.
     * Dates et heures avec séparateurs, numéros de téléphone par paires, références avec tirets : acceptés.
     */
    static boolean looksSensitive(String note) {
        String text = STRIPE_TOKEN.matcher(UUID_TOKEN.matcher(note).replaceAll(" ")).replaceAll(" ");
        if (DIGITS_RUN.matcher(text).find() || IBAN_GROUPS.matcher(text).find()) {
            return true;
        }
        for (String token : text.split("[^A-Za-z0-9-]+")) {
            if (token.indexOf('-') < 0 && token.length() >= 9 && token.chars().anyMatch(Character::isDigit)) {
                return true;
            }
        }
        return false;
    }

    public List<Dac7RefundRow> refunds(Integer yearValue) {
        Declaration d = declarations.declaration(declarations.requireYear(yearValue),
                Dac7DeclarationService.IdentityMode.NONE);
        return d.report().refundsToReconcile().stream()
                .map(r -> Dac7RefundRow.of(r, d.displayNames().get(r.travelerId()))).toList();
    }

    public List<Dac7AnomalyRow> anomalies(Integer yearValue) {
        Declaration d = declarations.declaration(declarations.requireYear(yearValue),
                Dac7DeclarationService.IdentityMode.NONE);
        return d.report().anomalies().stream()
                .map(a -> Dac7AnomalyRow.of(a, a.travelerId() == null ? null : d.displayNames().get(a.travelerId())))
                .toList();
    }

    private static SellerFilter filter(String value) {
        if (value == null || value.isBlank()) {
            return SellerFilter.ALL;
        }
        try {
            return SellerFilter.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-filter-invalid", "problem.dac7.dac7-filter-invalid");
        }
    }
}
