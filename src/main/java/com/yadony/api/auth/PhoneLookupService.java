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
 * <p>Coût Firebase, et ce qu'il laisse deviner : une recherche de propriétaire, puis, pour
 * {@link #findByPhone}, UNE lecture groupée ({@code getContacts}) seulement si au moins un candidat
 * est au niveau YADONY_VERIFIED (le seul cas où le claim peut changer l'issue d'un appelant). Un
 * numéro simplement déclaré ou inconnu ne déclenche aucune lecture. Différence résiduelle : un compte
 * validé par Yadony ajoute une lecture groupée (le cache Caffeine de 5 min de
 * {@link FirebaseContactService} l'atténue sans l'effacer). {@link #findAllByPhone}, réservé à
 * l'admin, lit toujours les candidats.
 *
 */
@Component
public class PhoneLookupService {

    /** Plafond de lignes lues ; couplé au nom de {@link UserRepository#findTop10ByDeclaredPhoneHash}. */
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

    /**
     * Numéro déclaré sans SMS que Yadony a validé, quand il est de confiance (niveau YADONY_VERIFIED ET
     * empreinte concordant avec le claim courant, cf. {@link DeclaredPhoneResolver}). Vide pour un simple
     * numéro déclaré, un niveau périmé ou un compte qui a un numéro vérifié par SMS (alors seul celui-là
     * compte). Sert aux lecteurs qui prennent « le téléphone de l'invité » : SMS d'abord, ce numéro ensuite.
     */
    public Optional<String> trustedDeclaredPhone(UserEntity user, FirebaseContactService.Contact contact) {
        if (user == null) {
            return Optional.empty();
        }
        DeclaredPhoneResolver.Resolved resolved = resolver.resolve(user, contact);
        return resolved.level() == DeclaredPhoneLevel.YADONY_VERIFIED
                ? Optional.of(resolved.number()) : Optional.empty();
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
        // Un simple déclarant n'a jamais d'effet sur les appelants : sans compte validé par Yadony,
        // aucune lecture Firebase (voir la Javadoc de classe).
        List<UserEntity> rows = candidateRows(e164);
        if (rows.stream().noneMatch(u -> u.getDeclaredPhoneLevel() == DeclaredPhoneLevel.YADONY_VERIFIED)) {
            return new PhoneMatch.None();
        }
        // Le compte validé par Yadony l'emporte sur les simples déclarants, comme le propriétaire SMS.
        List<PhoneMatch.Found> yadony = confirm(rows, e164).stream()
                .filter(f -> f.trust() == PhoneMatch.Trust.YADONY)
                .toList();
        return switch (yadony.size()) {
            case 0 -> new PhoneMatch.None();
            case 1 -> yadony.get(0);
            default -> new PhoneMatch.Ambiguous();
        };
    }

    /**
     * Tous les comptes que ce numéro désigne, pour la recherche admin : le propriétaire vérifié
     * par SMS ET les déclarants confirmés (le support doit pouvoir retrouver un squatteur). Le plafond
     * {@value #MAX_CANDIDATES} de lignes s'applique aussi ici.
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
        List<UserEntity> rows = candidateRows(e164);
        for (PhoneMatch.Found declared : rows.isEmpty() ? List.<PhoneMatch.Found>of() : confirm(rows, e164)) {
            if (all.stream().noneMatch(u -> u.getId() != null && u.getId().equals(declared.user().getId()))) {
                all.add(declared.user());
            }
        }
        return all;
    }

    /** Lignes en base portant l'empreinte du numéro, plafonnées et hors comptes supprimés ; aucun appel Firebase. */
    private List<UserEntity> candidateRows(String e164) {
        if (!hasher.isConfigured()) {
            return List.of();
        }
        return userRepository.findTop10ByDeclaredPhoneHash(hasher.hash(e164)).stream()
                .filter(u -> u.getDeletedAt() == null)
                .limit(MAX_CANDIDATES)
                .toList();
    }

    /** Garde les lignes que le claim Firebase confirme encore, en une seule lecture groupée. */
    private List<PhoneMatch.Found> confirm(List<UserEntity> rows, String e164) {
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
