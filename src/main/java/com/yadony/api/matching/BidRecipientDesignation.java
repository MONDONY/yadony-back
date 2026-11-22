package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.RecipientAccount;
import com.yadony.api.common.RecipientAccountResolver;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Désignation du destinataire d'un colis à sa création : soit nom + numéro, soit l'identifiant d'un compte Yadony
 * (jamais les deux). Le compte est résolu par l'interface de {@code common/}, sans dépendre du package d'annuaire.
 */
@Component
public class BidRecipientDesignation {

    static final String AUDIT_ACTION = "RECIPIENT_DESIGNATED_BY_USERNAME";

    private final RecipientAccountResolver resolver;
    private final AuditService auditService;

    public BidRecipientDesignation(RecipientAccountResolver resolver, AuditService auditService) {
        this.resolver = resolver;
        this.auditService = auditService;
    }

    /**
     * Pose le destinataire sur {@code bid} (avant son enregistrement). Avec un identifiant : {@code recipientUserId}
     * renseigné, {@code recipientName = "@identifiant"}, numéro nul. Sans identifiant : nom et numéro tels quels.
     */
    public void apply(BidEntity bid, UUID senderId, UUID travelerId, String name, String phone, String username) {
        if (isBlank(username)) {
            bid.setRecipientName(name);
            bid.setRecipientPhone(phone);
            return;
        }
        if (!isBlank(name) || !isBlank(phone)) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "recipient-mode-conflict",
                    "problem.recipient-mode-conflict");
        }
        RecipientAccount account = resolver.resolve(senderId, username);
        if (account.userId().equals(travelerId)) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "recipient-is-traveler",
                    "problem.recipient-is-traveler");
        }
        bid.setRecipientUserId(account.userId());
        bid.setRecipientName("@" + account.username());
        bid.setRecipientPhone(null);
    }

    /** Négociation : un destinataire est obligatoire (nom + numéro, ou identifiant), comme avant ce mode. */
    public void requireRecipient(String name, String phone, String username) {
        if (isBlank(username) && (isBlank(name) || isBlank(phone))) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "recipient-required",
                    "problem.recipient-required");
        }
    }

    /** Trace la désignation par identifiant, à appeler une fois le colis enregistré (id connu). Jamais de numéro. */
    public void audit(BidEntity savedBid, UUID senderId) {
        if (savedBid.getRecipientUserId() == null) {
            return;
        }
        auditService.log("BID", savedBid.getId(), AUDIT_ACTION, senderId,
                Map.of("recipientUserId", savedBid.getRecipientUserId().toString()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
