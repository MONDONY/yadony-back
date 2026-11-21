package com.yadony.api.cancellation.fee;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Frais retenus sur le remboursement d'un expéditeur qui annule : interrupteur, date d'effet et barème.
 *
 * @param enabled       interrupteur : éteint sauf {@code true} explicite ({@code YADONY_CANCELLATION_FEE_ENABLED}). À
 *                      n'allumer qu'une fois l'app qui montre les frais publiée et les CGU à jour. L'éteindre ne fait
 *                      que cesser d'en enregistrer de nouveaux : une retenue déjà due ou réglée suit son cours
 *                      (remboursement partiel, versement au voyageur), l'argent promis n'est jamais abandonné.
 * @param effectiveFrom date d'effet ({@code YADONY_CANCELLATION_FEE_EFFECTIVE_FROM}) : seuls les colis CRÉÉS à cet instant
 *                      ou après peuvent porter des frais, un colis plus ancien a été payé sous des CGU sans frais. Lue en
 *                      TEXTE et analysée strictement ({@link #parseEffectiveFrom}) : instant ISO-8601 avec date, heure et
 *                      fuseau ({@code 2026-12-15T00:00:00Z} ou {@code 2026-12-15T00:00:00+01:00}). Des chiffres seuls
 *                      ({@code 20261215}, lus sinon comme des millisecondes depuis 1970) ou une date sans heure refusent le
 *                      démarrage ; allumée, une date antérieure à {@link #EARLIEST_EFFECTIVE_FROM} aussi, pour qu'aucune
 *                      faute de saisie ne couvre les colis existants. Vide : aucun frais. Allumé sans date d'effet,
 *                      l'interrupteur reste éteint (avertissement au démarrage, {@link CancellationFeeEligibility}).
 * @param capEur        plafond du frais par annulation, en euros (30 par défaut), jamais négatif.
 * @param tiers         paliers, lus du plus lointain au plus proche du départ (triés ici par
 *                      {@code minDaysBefore} décroissant, chaque seuil unique et positif, chaque taux dans [0, 1]). Le
 *                      temps restant avant le départ est comparé exactement au seuil (sans arrondi à l'heure) ; un palier
 *                      s'applique quand il reste <b>au moins</b> {@code minDaysBefore} jours, sauf le palier le plus
 *                      lointain (fenêtre gratuite) qui exige <b>strictement plus</b> : avec
 *                      {@code 5 -> 0, 3 -> 0.10, 1 -> 0.20, 0 -> 0.30}, plus de 5 jours = 0 %, de 3 à 5 jours inclus
 *                      = 10 %, de 1 jour inclus à moins de 3 jours = 20 %, moins d'un jour ou après le départ = 30 %.
 */
@ConfigurationProperties(prefix = "yadony.cancellation-fee")
public record CancellationFeeProperties(boolean enabled, Instant effectiveFrom, BigDecimal capEur, List<Tier> tiers) {

    private static final String PREFIX = "yadony.cancellation-fee";

    /**
     * Plancher de la date d'effet quand les frais sont allumés : la fonction n'existait pas avant, une date plus ancienne
     * ne peut être qu'une faute de saisie qui ferait payer des colis réglés sous des CGU sans frais.
     */
    public static final Instant EARLIEST_EFFECTIVE_FROM = Instant.parse("2026-11-01T00:00:00Z");

    private static final String EXPECTED_FORMAT = "2026-12-15T00:00:00Z";

    /** @param minDaysBefore seuil du palier en jours avant le départ ; @param rate taux (0.10 = 10 %). */
    public record Tier(int minDaysBefore, BigDecimal rate) {}

    /**
     * Barème validé : une valeur invalide lève {@link IllegalArgumentException}, et l'application refuse de démarrer
     * plutôt que de retenir un taux hors de [0, 1] ou un plafond négatif. Constructeur du code et des tests : la
     * configuration passe par le constructeur de liaison ci-dessous, qui analyse la date d'effet.
     */
    public CancellationFeeProperties {
        if (capEur == null) capEur = new BigDecimal("30");
        if (capEur.signum() < 0) {
            throw new IllegalArgumentException(PREFIX + ".cap-eur : plafond négatif refusé (valeur reçue : "
                    + capEur.toPlainString() + ")");
        }
        tiers = tiers == null || tiers.isEmpty()
                ? List.of(new Tier(5, BigDecimal.ZERO), new Tier(3, new BigDecimal("0.10")),
                        new Tier(1, new BigDecimal("0.20")), new Tier(0, new BigDecimal("0.30")))
                : tiers.stream().sorted(Comparator.comparingInt(Tier::minDaysBefore).reversed()).toList();
        validate(tiers);
    }

    /**
     * Liaison de la configuration ({@code yadony.cancellation-fee.*}) : date d'effet reçue en texte, analysée strictement
     * ({@link #parseEffectiveFrom}), jamais par le convertisseur de Spring (qui lit des chiffres seuls comme des
     * millisecondes depuis 1970, sans erreur).
     *
     * @param allowPastEffectiveFrom RÉSERVÉ AU PROFIL DE TEST DE LA FONCTION ({@code application-cancellation-fee.yml}
     *                               de {@code src/test}, activé par les seuls tests des frais) : autorise une date d'effet
     *                               antérieure à {@link #EARLIEST_EFFECTIVE_FROM}, pour que les colis des tests soient
     *                               tous « nouveaux ». Faux par défaut ; jamais dans les profils de test partagés, dans
     *                               {@code src/main}, les workflows ni les fichiers d'environnement des conteneurs
     *                               (vérifié par {@code CancellationFeePropertiesTest}).
     */
    @ConstructorBinding
    public CancellationFeeProperties(boolean enabled, String effectiveFrom, BigDecimal capEur, List<Tier> tiers,
                                     boolean allowPastEffectiveFrom) {
        this(enabled, checkedEffectiveFrom(enabled, parseEffectiveFrom(effectiveFrom), allowPastEffectiveFrom),
                capEur, tiers);
    }

    /**
     * Date d'effet stricte : instant ISO-8601 avec date, heure ET fuseau ou décalage ({@code 2026-12-15T00:00:00Z},
     * {@code 2026-12-15T00:00:00+01:00}). Vide ou absente : {@code null} (aucun frais).
     *
     * @throws IllegalArgumentException chiffres seuls, date sans heure, heure sans fuseau, texte illisible
     */
    static Instant parseEffectiveFrom(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            // Sans la cause : ce message est la raison affichée au refus de démarrage, il dit déjà tout.
            throw new IllegalArgumentException(PREFIX + ".effective-from (YADONY_CANCELLATION_FEE_EFFECTIVE_FROM) : "
                    + "date d'effet « " + value + " » illisible, attendu un instant ISO-8601 avec date, heure et fuseau, "
                    + "ex. " + EXPECTED_FORMAT + " / invalid effective date « " + value + " », expected an ISO-8601 "
                    + "instant with date, time and offset, e.g. " + EXPECTED_FORMAT);
        }
    }

    /** Allumés, jamais une date d'effet d'avant la fonction (sauf autorisation des profils de test). */
    private static Instant checkedEffectiveFrom(boolean enabled, Instant effectiveFrom, boolean allowPast) {
        if (enabled && effectiveFrom != null && !allowPast && effectiveFrom.isBefore(EARLIEST_EFFECTIVE_FROM)) {
            throw new IllegalArgumentException(PREFIX + ".effective-from (YADONY_CANCELLATION_FEE_EFFECTIVE_FROM) : "
                    + "date d'effet " + effectiveFrom + " antérieure au " + EARLIEST_EFFECTIVE_FROM + ", elle couvrirait "
                    + "des colis payés sans frais, ex. attendu " + EXPECTED_FORMAT + " / effective date " + effectiveFrom
                    + " is before " + EARLIEST_EFFECTIVE_FROM + " and would cover parcels paid without fees");
        }
        return effectiveFrom;
    }

    /** Barème seul, interrupteur éteint : calculs et tests qui ne dépendent pas de l'activation. */
    public CancellationFeeProperties(BigDecimal capEur, List<Tier> tiers) {
        this(false, null, capEur, tiers);
    }

    private static void validate(List<Tier> tiers) {
        Set<Integer> seen = new HashSet<>();
        for (Tier tier : tiers) {
            if (tier.rate() == null || tier.rate().signum() < 0 || tier.rate().compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(PREFIX + ".tiers : rate doit être compris entre 0 et 1 (palier "
                        + tier.minDaysBefore() + " j, valeur reçue : "
                        + (tier.rate() == null ? "absente" : tier.rate().toPlainString()) + ")");
            }
            if (tier.minDaysBefore() < 0) {
                throw new IllegalArgumentException(PREFIX + ".tiers : min-days-before négatif refusé (valeur reçue : "
                        + tier.minDaysBefore() + ")");
            }
            if (!seen.add(tier.minDaysBefore())) {
                throw new IllegalArgumentException(PREFIX + ".tiers : min-days-before " + tier.minDaysBefore()
                        + " en double, chaque palier doit avoir son propre seuil");
            }
        }
    }
}
