package com.yadony.api.matching;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.reception.ReceptionPhones;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Le destinataire du colis est-il l'expéditeur lui-même ? Vrai si le compte désigné par identifiant est
 * l'expéditeur, ou si le numéro du destinataire est celui de l'expéditeur. Le nom seul ne compte jamais : deux
 * personnes peuvent se prénommer pareil.
 */
public final class RecipientIsSender {

    private static final Pattern NON_DIGITS = Pattern.compile("\\D");
    /** Un numéro national (sans indicatif) n'est rapproché qu'au-delà de ce nombre de chiffres. */
    private static final int MIN_NATIONAL_DIGITS = 8;

    private RecipientIsSender() {}

    /** Comparaison par identifiant seulement : le numéro de l'expéditeur n'est pas connu ici. */
    public static boolean of(BidEntity bid, UserEntity sender) {
        return of(bid, sender, null);
    }

    /**
     * @param senderPhone numéro de l'expéditeur tel que Firebase le sert (vérifié par SMS, sinon déclaré),
     *                    null si inconnu : seul l'identifiant compte alors.
     */
    public static boolean of(BidEntity bid, UserEntity sender, String senderPhone) {
        if (bid == null || sender == null) {
            return false;
        }
        if (bid.getRecipientUserId() != null) {
            return bid.getRecipientUserId().equals(sender.getId());
        }
        return samePhone(bid.getRecipientPhone(), senderPhone);
    }

    /**
     * Décision complète côté expéditeur, partagée par la réponse API et les push : l'identifiant d'abord ; le
     * numéro de l'expéditeur (Firebase, cache Caffeine) n'est lu que s'il sert, soit colis sans compte désigné et
     * avec un numéro de destinataire. {@code firebaseContact} null (câblage optionnel) : identifiant seul.
     */
    public static boolean resolve(BidEntity bid, UserEntity sender, FirebaseContactService firebaseContact) {
        if (bid == null || sender == null) {
            return false;
        }
        boolean needsPhone = bid.getRecipientUserId() == null
                && bid.getRecipientPhone() != null && !bid.getRecipientPhone().isBlank();
        if (!needsPhone || firebaseContact == null) {
            return of(bid, sender);
        }
        FirebaseContactService.Contact contact = firebaseContact.getContact(sender.getFirebaseUid());
        if (contact == null) {
            return of(bid, sender);
        }
        String phone = contact.phoneNumber() != null && !contact.phoneNumber().isBlank()
                ? contact.phoneNumber() : contact.declaredPhone();
        return of(bid, sender, phone);
    }

    /**
     * Même numéro, quel que soit le format saisi. Le normaliseur du back ne devine jamais un indicatif : un
     * numéro national (« 06 12 34 56 78 ») est donc rapproché à la fin du numéro international de
     * l'expéditeur, au moins 8 chiffres significatifs, jamais un numéro plus court.
     */
    static boolean samePhone(String recipientPhone, String senderPhone) {
        Optional<String> sender = ReceptionPhones.toE164(senderPhone);
        if (sender.isEmpty() || recipientPhone == null || recipientPhone.isBlank()) {
            return false;
        }
        Optional<String> recipient = ReceptionPhones.toE164(recipientPhone);
        if (recipient.isPresent()) {
            return recipient.get().equals(sender.get());
        }
        String digits = NON_DIGITS.matcher(recipientPhone).replaceAll("");
        if (digits.startsWith("0") && !digits.startsWith("00")) {
            digits = digits.substring(1);
        } else {
            return false;
        }
        String senderDigits = sender.get().substring(1);
        return digits.length() >= MIN_NATIONAL_DIGITS
                && senderDigits.length() > digits.length()
                && senderDigits.endsWith(digits);
    }
}
