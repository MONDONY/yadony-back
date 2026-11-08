package com.yadony.api.smsotp;

/**
 * Un compte connecté a demandé des codes SMS pour {@code distinctNumbers} numéros différents
 * sur 24 h, au moins {@code threshold} : signe de pompage de SMS (08/11/2026), pas d'un usage
 * normal (on ajoute UN numéro à son profil). Écouté par le package {@code auth}, qui suspend le
 * compte et alerte le back-office.
 *
 * <p>Publié de façon synchrone dans la transaction d'envoi, que {@link SmsOtpService} annule
 * aussitôt en refusant la demande : l'écouteur doit écrire dans sa propre transaction.
 *
 * @param firebaseUid UID Firebase du compte (jamais un invité)
 */
public record SmsOtpNumberHoppingEvent(String firebaseUid, int distinctNumbers, int threshold) {
}
