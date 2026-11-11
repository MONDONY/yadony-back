package com.yadony.api.dac7.dto;

/** {@code POST /admin/dac7/sellers/{userId}/decision} : note libre de 1 à 500 caractères, sans donnée sensible. */
public record Dac7DecisionRequest(String note) {

    @Override
    public String toString() {
        return "Dac7DecisionRequest[note masquée]";
    }
}
