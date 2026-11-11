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
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "dac7-collection-disabled", "DAC7 Disabled",
                    "La collecte DAC7 est éteinte : aucune demande n'est envoyée aux voyageurs.");
        }
        Dac7ReminderService.Outcome outcome = reminders.remindByAdmin(userId, adminId, clock.instant());
        switch (outcome) {
            case SENT -> audit.log("USER", userId, "DAC7_REMINDER_SENT", adminId, Map.of("channel", "ADMIN"));
            case NOT_FOUND -> throw new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found",
                    "User Not Found", "Utilisateur introuvable");
            case TOO_SOON -> throw new YadonyBusinessException(HttpStatus.CONFLICT, "dac7-remind-too-soon",
                    "Remind Too Soon", "Un message a déjà été envoyé à ce voyageur il y a moins de 7 jours.");
            case EXHAUSTED -> throw new YadonyBusinessException(HttpStatus.CONFLICT, "dac7-reminders-exhausted",
                    "Reminders Exhausted", "La demande et les deux rappels ont déjà été envoyés : la suite est une "
                    + "décision de l'équipe.");
            default -> throw new YadonyBusinessException(HttpStatus.CONFLICT, "dac7-not-solicitable",
                    "Not Solicitable", "Ce voyageur n'est pas à solliciter (déjà fourni, hors périmètre ou jamais payé).");
        }
    }

    public void decision(UUID userId, UUID adminId, String note) {
        String cleaned = note == null ? "" : note.strip();
        if (cleaned.isEmpty() || cleaned.length() > Dac7DueDiligenceService.MAX_NOTE_LENGTH) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-note-invalid", "Invalid Note",
                    "La note doit compter de 1 à " + Dac7DueDiligenceService.MAX_NOTE_LENGTH + " caractères.");
        }
        if (looksSensitive(cleaned)) {
            // Jamais recopiée : elle pourrait contenir un NIF ou un numéro de compte.
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-note-sensitive",
                    "Sensitive Note", "La note semble contenir un numéro (9 chiffres ou plus) : ni NIF, ni numéro de "
                    + "TVA, ni numéro de compte dans le registre.");
        }
        if (!reads.userExists(userId)) {
            throw new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found",
                    "Utilisateur introuvable");
        }
        diligence.record(userId, Dac7DueDiligenceAction.TEAM_DECISION, Dac7DueDiligenceChannel.ADMIN, adminId, cleaned);
        // Le fait seulement : la note reste au registre, jamais dans l'audit.
        audit.log("USER", userId, "DAC7_TEAM_DECISION", adminId, Map.of("noteLength", cleaned.length()));
    }

    /** 9 chiffres ou plus d'affilée, séparateurs usuels (espace, point, tiret, barre oblique) ignorés. */
    static boolean looksSensitive(String note) {
        return note.replaceAll("[\\s./-]", "").matches("(?s).*\\d{9,}.*");
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
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "dac7-filter-invalid",
                    "Invalid Filter", "Filtre inconnu.");
        }
    }
}
