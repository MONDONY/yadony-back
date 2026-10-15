package com.yadony.api.matching.reception.dto;

/** Lien entre l'utilisateur connecté et un colis, pour router le lien yadony://colis/{id}. */
public record ParcelRoleResponse(Role role) {

    public enum Role { SENDER, TRAVELER, RECIPIENT, NONE }
}
