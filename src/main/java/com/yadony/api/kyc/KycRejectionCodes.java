package com.yadony.api.kyc;

import java.util.List;

/**
 * Catalogue ferme des codes qu'un administrateur peut opposer a une verification (refus ou
 * revocation).
 *
 * <p>Reprend les codes que l'application sait deja traduire ({@code kyc_rejection_messages.dart}
 * cote dony_app) : l'utilisateur lit donc un message precis, jamais le motif interne de
 * l'administrateur. {@code suspected_fraud} et {@code other} n'ont pas de message dedie :
 * l'application affiche son message generique.
 */
public final class KycRejectionCodes {

    public static final List<String> ALL = List.of(
            "document_expired",
            "document_type_not_supported",
            "document_unverified_other",
            "country_not_supported",
            "id_number_insufficient_document_data",
            "id_number_mismatch",
            "id_number_unverified_other",
            "selfie_document_missing_photo",
            "selfie_face_mismatch",
            "selfie_manipulated",
            "selfie_unverified_other",
            "under_supported_age",
            "consent_declined",
            "session_canceled",
            "suspected_fraud",
            "other");

    private KycRejectionCodes() {
    }

    public static boolean isValid(String code) {
        return code != null && ALL.contains(code);
    }
}
