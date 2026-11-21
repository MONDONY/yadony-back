package com.yadony.api.auth;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Retrouve un compte par son numéro, vérifié par SMS ou déclaré sans SMS.
 *
 * <p>Le propriétaire vérifié par SMS gagne toujours : {@link FirebaseContactService#findUidByPhone}
 * continue de ne voir que le {@code phoneNumber} officiel Firebase. Un numéro déclaré ne sert que
 * s'il n'a pas de propriétaire vérifié, et seulement si la correspondance est fiable : l'empreinte
 * en base ne suffit pas, le claim Firebase du compte doit encore porter ce numéro, et le compte ne
 * doit pas avoir de numéro vérifié par SMS (ses données déclarées sont alors ignorées).
 */
@Component
public class PhoneLookupService {

    private final FirebaseContactService firebaseContact;
    private final UserRepository userRepository;
    private final DeclaredPhoneHasher hasher;

    public PhoneLookupService(FirebaseContactService firebaseContact,
                              UserRepository userRepository,
                              DeclaredPhoneHasher hasher) {
        this.firebaseContact = firebaseContact;
        this.userRepository = userRepository;
        this.hasher = hasher;
    }

    public PhoneMatch findByPhone(String e164) {
        List<UserEntity> matches = findAllByPhone(e164);
        return switch (matches.size()) {
            case 0 -> new PhoneMatch.None();
            case 1 -> new PhoneMatch.Found(matches.get(0));
            default -> new PhoneMatch.Ambiguous();
        };
    }

    /**
     * Tous les comptes que ce numéro désigne : le propriétaire vérifié seul s'il existe, sinon
     * les comptes dont le numéro déclaré est confirmé par leur claim. Pour la recherche admin,
     * qui liste les doublons au lieu de les écarter.
     */
    public List<UserEntity> findAllByPhone(String e164) {
        if (e164 == null || e164.isBlank()) {
            return List.of();
        }
        Optional<String> smsOwner = firebaseContact.findUidByPhone(e164);
        if (smsOwner.isPresent()) {
            return userRepository.findByFirebaseUid(smsOwner.get()).map(List::of).orElse(List.of());
        }
        if (!hasher.isConfigured()) {
            return List.of();
        }
        return userRepository.findAllByDeclaredPhoneHash(hasher.hash(e164)).stream()
                .filter(u -> u.getDeletedAt() == null)
                .filter(u -> claimConfirms(u, e164))
                .toList();
    }

    private boolean claimConfirms(UserEntity user, String e164) {
        FirebaseContactService.Contact contact = firebaseContact.getContact(user.getFirebaseUid());
        boolean hasSmsNumber = contact.phoneNumber() != null && !contact.phoneNumber().isBlank();
        return !hasSmsNumber && e164.equals(contact.declaredPhone());
    }
}
