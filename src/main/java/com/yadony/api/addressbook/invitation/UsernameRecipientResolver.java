package com.yadony.api.addressbook.invitation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.RecipientAccount;
import com.yadony.api.common.RecipientAccountResolver;
import com.yadony.api.common.YadonyNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Résolution d'un destinataire par identifiant, sur la même règle et le même plafond que la recherche. */
@Component
public class UsernameRecipientResolver implements RecipientAccountResolver {

    private final UsernameLookupService lookupService;
    private final UserRepository userRepository;

    public UsernameRecipientResolver(UsernameLookupService lookupService, UserRepository userRepository) {
        this.lookupService = lookupService;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public RecipientAccount resolve(UUID callerUserId, String rawUsername) {
        UserEntity caller = userRepository.findById(callerUserId)
                .orElseThrow(() -> YadonyNotFoundException.localized("problem.kyc.user-not-found"));
        lookupService.beginLookup(callerUserId, rawUsername);
        UserEntity found = lookupService.resolveVisible(caller, rawUsername)
                .orElseThrow(UsernameLookupService::notFound);
        if (found.getId().equals(callerUserId)) {
            throw UsernameLookupService.selfInvitation();
        }
        return new RecipientAccount(found.getId(), found.getUsername());
    }
}
