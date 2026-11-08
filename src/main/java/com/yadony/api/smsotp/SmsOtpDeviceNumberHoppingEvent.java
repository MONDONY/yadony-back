package com.yadony.api.smsotp;

/**
 * Un téléphone SANS compte connecté (écran de connexion) a demandé des codes SMS pour
 * {@code distinctNumbers} numéros différents sur 24 h, au moins {@code threshold} : possible
 * robot ou pompage de SMS. Alerte seulement, il n'y a pas de compte à suspendre. Écouté par le
 * package {@code admin}, qui lève l'alerte du back-office.
 *
 * @param deviceKey {@code device:<identifiant d'appareil de l'app>} ou {@code guest:<uid invité>}
 */
public record SmsOtpDeviceNumberHoppingEvent(String deviceKey, int distinctNumbers, int threshold) {
}
