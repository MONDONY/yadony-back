package com.yadony.api.matching.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Validation des DTOs de négociation")
class BidNegotiationRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private BidNegotiationStartRequest valid(BigDecimal proposed, List<BidCustomItemRequest> custom) {
        return new BidNegotiationStartRequest(
                new BigDecimal("5.0"), "Vêtements", "CLOTHING",
                "Aminata Diallo", "+221701234567", true,
                "CASH", null, null, null,
                proposed, custom, null);
    }

    @Test
    @DisplayName("une proposition valide ne produit aucune violation")
    void validRequestPasses() {
        assertThat(validator.validate(valid(new BigDecimal("45.00"), null))).isEmpty();
    }

    @Test
    @DisplayName("le montant proposé est obligatoire")
    void proposedAmountIsRequired() {
        assertThat(validator.validate(valid(null, null))).isNotEmpty();
    }

    @Test
    @DisplayName("un montant hors bornes est refusé")
    void proposedAmountOutOfBoundsRejected() {
        assertThat(validator.validate(valid(new BigDecimal("0.00"), null))).isNotEmpty();
        assertThat(validator.validate(valid(new BigDecimal("1000001"), null))).isNotEmpty();
    }

    @Test
    @DisplayName("une ligne hors grille sans libellé est refusée")
    void customItemNeedsLabel() {
        var items = List.of(new BidCustomItemRequest("  ", 1, new BigDecimal("20.00")));
        assertThat(validator.validate(valid(new BigDecimal("45.00"), items))).isNotEmpty();
    }

    @Test
    @DisplayName("plus de 10 lignes hors grille est refusé")
    void customItemsAreCapped() {
        var one = new BidCustomItemRequest("Tapis", 1, new BigDecimal("5.00"));
        var items = java.util.Collections.nCopies(11, one);
        assertThat(validator.validate(valid(new BigDecimal("45.00"), items))).isNotEmpty();
    }

    @Test
    @DisplayName("une ligne hors grille sans montant est refusée")
    void customItemNeedsAmount() {
        var items = List.of(new BidCustomItemRequest("Tapis", 1, null));
        assertThat(validator.validate(valid(new BigDecimal("45.00"), items))).isNotEmpty();
    }

    @Test
    @DisplayName("une quantité hors bornes est refusée")
    void customItemQuantityIsBounded() {
        assertThat(validator.validate(valid(new BigDecimal("45.00"),
                List.of(new BidCustomItemRequest("Tapis", 0, new BigDecimal("5.00")))))).isNotEmpty();
        assertThat(validator.validate(valid(new BigDecimal("45.00"),
                List.of(new BidCustomItemRequest("Tapis", 100, new BigDecimal("5.00")))))).isNotEmpty();
    }

    @Test
    @DisplayName("la contre-offre exige un montant dans les bornes et un message court")
    void counterRequestIsValidated() {
        assertThat(validator.validate(
                new BidNegotiationCounterRequest(new BigDecimal("40.00"), "Je propose 40"))).isEmpty();
        assertThat(validator.validate(
                new BidNegotiationCounterRequest(null, null))).isNotEmpty();
        assertThat(validator.validate(
                new BidNegotiationCounterRequest(new BigDecimal("0.00"), null))).isNotEmpty();
        assertThat(validator.validate(
                new BidNegotiationCounterRequest(new BigDecimal("1000001"), null))).isNotEmpty();
        assertThat(validator.validate(
                new BidNegotiationCounterRequest(new BigDecimal("40.00"), "x".repeat(281)))).isNotEmpty();
    }

    @Test
    @DisplayName("la description, la catégorie et le destinataire sont obligatoires")
    void mandatoryFieldsAreEnforced() {
        var missingDescription = new BidNegotiationStartRequest(
                new BigDecimal("5.0"), "  ", "CLOTHING",
                "Aminata Diallo", "+221701234567", true,
                "CASH", null, null, null,
                new BigDecimal("45.00"), null, null);
        assertThat(validator.validate(missingDescription)).isNotEmpty();

        var missingDisclaimer = new BidNegotiationStartRequest(
                new BigDecimal("5.0"), "Vêtements", "CLOTHING",
                "Aminata Diallo", "+221701234567", null,
                "CASH", null, null, null,
                new BigDecimal("45.00"), null, null);
        assertThat(validator.validate(missingDisclaimer)).isNotEmpty();
    }
}
