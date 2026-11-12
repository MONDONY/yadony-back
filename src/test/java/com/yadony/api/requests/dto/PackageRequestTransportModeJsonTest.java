package com.yadony.api.requests.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.dto.AdminPackageRequestDetailResponse;
import com.yadony.api.admin.dto.AdminPackageRequestListItemResponse;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrat du lot 0 « modes de transport » : une demande de colis « peu importe » a
 * {@code transportMode = null}, et les clients distinguent « clé absente = ancienne donnée
 * (avion) » de « clé présente à null = peu importe ». Le {@code ObjectMapper} de Spring est
 * configuré avec {@code default-property-inclusion: NON_NULL} : sans annotation au niveau du
 * champ, la clé disparaît et l'app affiche à tort « avion ».
 *
 * <p>Sérialisation réelle (ObjectMapper auto-configuré avec application.yml), pas un
 * {@code new ObjectMapper()}.
 */
@JsonTest
class PackageRequestTransportModeJsonTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void lAutoConfigurationGardeNonNullPourLesAutresChamps() throws Exception {
        // Garde-fou : si quelqu'un retire NON_NULL globalement, ce test ne prouve plus rien.
        JsonNode json = mapper.readTree(mapper.writeValueAsString(response(null)));
        assertThat(json.has("promoCode")).isFalse();
    }

    // ── Détail (PackageRequestResponse) ──────────────────────────────────────────────────

    @Test
    void detailPeuImporteExposeLaCleATexteNull() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(response(null)));
        assertThat(json.has("transportMode")).isTrue();
        assertThat(json.get("transportMode").isNull()).isTrue();
    }

    @Test
    void detailAvionExposeLaValeur() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(response(TransportMode.PLANE)));
        assertThat(json.get("transportMode").asText()).isEqualTo("PLANE");
    }

    // ── Recherche (PackageRequestSearchResponse) ─────────────────────────────────────────

    @Test
    void rechercheCartePeuImporteExposeLaCleANull() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(search(null)));
        assertThat(json.has("transportMode")).isTrue();
        assertThat(json.get("transportMode").isNull()).isTrue();
    }

    @Test
    void rechercheCarteAvionExposeLaValeur() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(search(TransportMode.PLANE)));
        assertThat(json.get("transportMode").asText()).isEqualTo("PLANE");
    }

    // ── Admin (liste + fiche d'une demande) ──────────────────────────────────────────────

    @Test
    void adminListePeuImporteExposeLaCleANull() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(adminListItem(null)));
        assertThat(json.has("transportMode")).isTrue();
        assertThat(json.get("transportMode").isNull()).isTrue();
        assertThat(mapper.readTree(mapper.writeValueAsString(adminListItem(TransportMode.PLANE)))
                .get("transportMode").asText()).isEqualTo("PLANE");
    }

    @Test
    void adminFichePeuImporteExposeLaCleANull() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(adminDetail(null)));
        assertThat(json.has("transportMode")).isTrue();
        assertThat(json.get("transportMode").isNull()).isTrue();
        assertThat(mapper.readTree(mapper.writeValueAsString(adminDetail(TransportMode.TRAIN)))
                .get("transportMode").asText()).isEqualTo("TRAIN");
    }

    // ── Fabriques ────────────────────────────────────────────────────────────────────────

    private static PackageRequestResponse response(TransportMode mode) {
        return new PackageRequestResponse(
                UUID.randomUUID(), UUID.randomUUID(),
                "Paris", "Dakar",
                LocalDate.of(2026, 12, 1), 2,
                new BigDecimal("3.0"), ParcelSize.SMALL,
                mode,
                "CLOTHES",
                "desc", new BigDecimal("20.00"), null,
                null, null,
                PackageRequestStatus.OPEN,
                LocalDateTime.of(2026, 11, 1, 10, 0),
                true,
                Set.of(PaymentMethod.STRIPE),
                null,
                List.of(),
                null,
                null,
                null,
                "EUR",
                Set.of(PaymentMethod.STRIPE),
                null,
                null,
                false);
    }

    private static PackageRequestSearchResponse search(TransportMode mode) {
        return new PackageRequestSearchResponse(
                UUID.randomUUID(),
                "Paris", "Dakar",
                null, null, null, null,
                LocalDate.of(2026, 12, 1), 2,
                new BigDecimal("3.0"), ParcelSize.SMALL,
                mode,
                "CLOTHES",
                new BigDecimal("20.00"), true, null,
                null, null,
                null,
                Set.of(PaymentMethod.STRIPE),
                List.of(),
                false,
                false,
                null, null, null,
                "EUR",
                Set.of(PaymentMethod.STRIPE),
                null, null, null, null, null);
    }

    private static AdminPackageRequestListItemResponse adminListItem(TransportMode mode) {
        return new AdminPackageRequestListItemResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Fatou S.",
                "Paris", "Dakar",
                LocalDate.of(2026, 12, 1),
                new BigDecimal("3.0"), ParcelSize.SMALL,
                mode,
                PackageRequestStatus.OPEN,
                "EUR", new BigDecimal("20.00"),
                LocalDateTime.of(2026, 11, 1, 10, 0),
                0L, 0L);
    }

    /** La fiche compte une quarantaine de champs : on la remplit par réflexion (valeurs neutres). */
    private static AdminPackageRequestDetailResponse adminDetail(TransportMode mode) {
        try {
            Constructor<?> ctor = AdminPackageRequestDetailResponse.class.getDeclaredConstructors()[0];
            RecordComponent[] comps = AdminPackageRequestDetailResponse.class.getRecordComponents();
            Object[] args = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                Class<?> t = comps[i].getType();
                if (t == TransportMode.class) args[i] = mode;
                else if (t == boolean.class) args[i] = false;
                else if (t == int.class) args[i] = 0;
                else if (t == long.class) args[i] = 0L;
                else if (t == List.class) args[i] = List.of();
                else args[i] = null;
            }
            return (AdminPackageRequestDetailResponse) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
