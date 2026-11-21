package com.yadony.api.auth;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Retrouve un compte par son numéro, vérifié par SMS ou déclaré sans SMS.
 *
 * <p>Le propriétaire vérifié par SMS gagne toujours : {@link FirebaseContactService#findPhoneOwner}
 * ne voit que le {@code phoneNumber} officiel Firebase. Sur une panne Firebase, la recherche
 * échoue fermée (aucun résultat) : un propriétaire SMS ne doit jamais céder la place à un
 * déclarant. Un numéro déclaré n'est pris en compte que si le claim Firebase du compte le
 * confirme encore et que le compte n'a pas de numéro vérifié par SMS.
 *
 * <p>{@link PhoneMatch.Found#trust()} dit d'où vient la confiance ; les appelants qui rattachent
 * un colis ou résolvent un invité n'acceptent que SMS et YADONY. Un numéro revendiqué par
 * plusieurs comptes confirmés (déclarés ou validés) est {@link PhoneMatch.Ambiguous}.
 *
 * <p>Coût Firebase : une recherche de propriétaire, puis au plus une lecture groupée
 * ({@code getContacts}) des au plus {@value #MAX_CANDIDATES} candidats. Reste une différence de
 * durée entre « aucun candidat » (pas de lecture groupée) et « candidats » (une lecture) ; elle
 * ne change jamais l'issue visible des appelants pour un simple déclarant.
 */
@Component
public class PhoneLookupService {

    static final int MAX_CANDIDATES = 10;

    private final FirebaseContactService firebaseContact;
    private final UserRepository userRepository;
    private final DeclaredPhoneHasher hasher;
    private final DeclaredPhoneResolver resolver;

    public PhoneLookupService(FirebaseContactService firebaseContact,
                              UserRepository userRepository,
                              DeclaredPhoneHasher hasher,
                              DeclaredPhoneResolver resolver) {
        this.firebaseContact = firebaseContact;
        this.userRepository = userRepository;
        this.hasher = hasher;
        this.resolver = resolver;
    }

    public PhoneMatch findByPhone(String e164) {
        if (e164 == null || e164.isBlank()) {
            return new PhoneMatch.None();
        }
        FirebaseContactService.PhoneOwnerLookup owner = firebaseContact.findPhoneOwner(e164);
        if (owner == null || owner.outcome() == FirebaseContactService.PhoneOwnerLookup.Outcome.ERROR) {
            return new PhoneMatch.None();
        }
        if (owner.outcome() == FirebaseContactService.PhoneOwnerLookup.Outcome.FOUND) {
            return userRepository.findByFirebaseUid(owner.uid())
                    .<PhoneMatch>map(u -> new PhoneMatch.Found(u, PhoneMatch.Trust.SMS))
                    .orElseGet(PhoneMatch.None::new);
        }
        List<PhoneMatch.Found> declared = declaredCandidates(e164);
        return switch (declared.size()) {
            case 0 -> new PhoneMatch.None();
            case 1 -> declared.get(0);
            default -> new PhoneMatch.Ambiguous();
        };
    }

    /**
     * Tous les comptes que ce numéro désigne, pour la recherche admin : le propriétaire vérifié
     * par SMS ET les déclarants confirmés (le support doit pouvoir retrouver un squatteur).
     */
    public List<UserEntity> findAllByPhone(String e164) {
        if (e164 == null || e164.isBlank()) {
            return List.of();
        }
        FirebaseContactService.PhoneOwnerLookup owner = firebaseContact.findPhoneOwner(e164);
        if (owner == null || owner.outcome() == FirebaseContactService.PhoneOwnerLookup.Outcome.ERROR) {
            return List.of();
        }
        List<UserEntity> all = new ArrayList<>();
        if (owner.outcome() == FirebaseContactService.PhoneOwnerLookup.Outcome.FOUND) {
            Optional<UserEntity> ownerUser = userRepository.findByFirebaseUid(owner.uid());
            ownerUser.ifPresent(all::add);
        }
        for (PhoneMatch.Found declared : declaredCandidates(e164)) {
            if (all.stream().noneMatch(u -> u.getId() != null && u.getId().equals(declared.user().getId()))) {
                all.add(declared.user());
            }
        }
        return all;
    }

    /** Comptes dont l'empreinte et le claim confirment ce numéro, en une seule lecture Firebase. */
    private List<PhoneMatch.Found> declaredCandidates(String e164) {
        if (!hasher.isConfigured()) {
            return List.of();
        }
        List<UserEntity> rows = userRepository.findTop10ByDeclaredPhoneHash(hasher.hash(e164)).stream()
                .filter(u -> u.getDeletedAt() == null)
                .limit(MAX_CANDIDATES)
                .toList();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, FirebaseContactService.Contact> contacts = firebaseContact.getContacts(
                rows.stream().map(UserEntity::getFirebaseUid).toList());
        List<PhoneMatch.Found> confirmed = new ArrayList<>();
        for (UserEntity user : rows) {
            FirebaseContactService.Contact contact =
                    contacts.getOrDefault(user.getFirebaseUid(), FirebaseContactService.Contact.EMPTY);
            DeclaredPhoneResolver.Resolved resolved = resolver.resolve(user, contact);
            if (e164.equals(resolved.number())) {
                confirmed.add(new PhoneMatch.Found(user, resolved.level() == DeclaredPhoneLevel.YADONY_VERIFIED
                        ? PhoneMatch.Trust.YADONY : PhoneMatch.Trust.DECLARED));
            }
        }
        return confirmed;
    }
}
