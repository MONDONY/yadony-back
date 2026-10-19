package com.yadony.api.common.commission;

import com.yadony.api.common.BaseEntity;
import com.yadony.api.payments.currency.SupportedCurrency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Version datée de la grille de commission (V331). Une grille en vigueur est immuable : un trigger
 * refuse tout UPDATE/DELETE dont {@code effective_from} est passé ; seule une grille programmée
 * s'annule, par {@code deleted_at}. Toutes les colonnes de contenu sont donc {@code updatable = false} :
 * l'UPDATE d'Hibernate ne réécrit que {@code deleted_at} et {@code updated_at}, jamais {@code effective_from}.
 *
 * <p>{@code tiers} et {@code minimums} sont des colonnes {@code jsonb}. Les montants y sont des chaînes
 * décimales : un double JSON ferait perdre la valeur exacte des bornes et des taux.
 * {@code @JdbcTypeCode(SqlTypes.JSON)} est OBLIGATOIRE (cf. {@code AdminAlertEntity}) : sans lui,
 * Hibernate 6 envoie un {@code varchar} que Postgres refuse pour une colonne jsonb.
 */
@Entity
@Table(name = "commission_grids")
@Where(clause = "deleted_at IS NULL")
public class CommissionGridEntity extends BaseEntity {

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDateTime effectiveFrom;

    /** {@code [{"fromEur":"0","rate":"0.07"}, ...]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tiers", nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<Map<String, String>> tiers;

    /** {@code {"EUR":"1.00","XOF":"650", ...}}, clé = nom de {@link SupportedCurrency}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "minimums", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, String> minimums;

    @Column(name = "note", length = 500, updatable = false)
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    protected CommissionGridEntity() { }

    public CommissionGridEntity(LocalDateTime effectiveFrom, List<CommissionTier> tiers,
                                Map<SupportedCurrency, BigDecimal> minimums, String note, UUID createdBy) {
        this.effectiveFrom = effectiveFrom;
        this.tiers = serializeTiers(tiers);
        this.minimums = serializeMinimums(minimums);
        this.note = note;
        this.createdBy = createdBy;
    }

    /** Vue de domaine, sans lien avec la session JPA : c'est elle que le cache conserve. */
    public CommissionGrid toGrid() {
        List<CommissionTier> parsedTiers = new ArrayList<>();
        for (Map<String, String> tier : tiers) {
            parsedTiers.add(new CommissionTier(new BigDecimal(tier.get("fromEur")), new BigDecimal(tier.get("rate"))));
        }
        Map<SupportedCurrency, BigDecimal> parsedMinimums = new LinkedHashMap<>();
        minimums.forEach((currency, amount) ->
                parsedMinimums.put(SupportedCurrency.valueOf(currency), new BigDecimal(amount)));
        return new CommissionGrid(getId(), effectiveFrom, parsedTiers, parsedMinimums);
    }

    /** Forme jsonb des tranches, aussi celle écrite dans le payload d'audit. */
    static List<Map<String, String>> serializeTiers(List<CommissionTier> tiers) {
        List<Map<String, String>> out = new ArrayList<>();
        for (CommissionTier tier : tiers) {
            Map<String, String> json = new LinkedHashMap<>();
            json.put("fromEur", tier.fromEur().toPlainString());
            json.put("rate", tier.rate().toPlainString());
            out.add(json);
        }
        return out;
    }

    /** Forme jsonb des minimums, aussi celle écrite dans le payload d'audit. */
    static Map<String, String> serializeMinimums(Map<SupportedCurrency, BigDecimal> minimums) {
        Map<String, String> out = new LinkedHashMap<>();
        for (SupportedCurrency currency : SupportedCurrency.values()) {
            BigDecimal amount = minimums.get(currency);
            if (amount != null) {
                out.put(currency.name(), amount.toPlainString());
            }
        }
        return out;
    }

    public LocalDateTime getEffectiveFrom() { return effectiveFrom; }

    public String getNote() { return note; }

    public UUID getCreatedBy() { return createdBy; }
}
