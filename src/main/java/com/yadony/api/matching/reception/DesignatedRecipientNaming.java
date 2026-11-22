package com.yadony.api.matching.reception;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.springframework.stereotype.Component;

/**
 * Colis désigné par identifiant : quand le lien du compte devient CONFIRMED (confirmation, ou lien d'emblée
 * confirmé d'un destinataire de confiance), le nom devient le nom public du compte et son numéro est copié,
 * seulement s'il ne le masque pas et s'il est connu. Jamais appelé pour un lien PENDING ; un numéro déjà copié
 * reste si le compte le masque ensuite ; un colis désigné par numéro n'est pas touché.
 */
@Component
public class DesignatedRecipientNaming {

    private final FirebaseContactService firebaseContact;
    private final BidRepository bidRepository;

    public DesignatedRecipientNaming(FirebaseContactService firebaseContact, BidRepository bidRepository) {
        this.firebaseContact = java.util.Objects.requireNonNull(firebaseContact);
        this.bidRepository = java.util.Objects.requireNonNull(bidRepository);
    }

    public void apply(BidEntity bid, UserEntity account) {
        if (bid.getRecipientUserId() == null || !bid.getRecipientUserId().equals(account.getId())) {
            return;
        }
        bid.setRecipientName(account.publicDisplayName());
        if (!account.isHidePhoneNumber()) {
            ReceptionPhones.toE164(firebaseContact.getContact(account.getFirebaseUid()).phoneNumber())
                    .ifPresent(bid::setRecipientPhone);
        }
        bidRepository.save(bid);
    }
}
