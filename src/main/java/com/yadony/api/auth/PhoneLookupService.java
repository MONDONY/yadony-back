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
 * plusieurs comptes validés par Yadony est {@link PhoneMatch.Ambiguous}.
 *
 * <p>Coût Firebase, et ce qu'il laisse deviner : une recherche de propriétaire, puis, pour
 * {@link #findByPhone}, UNE lecture groupée ({@code getContacts}) seulement si un compte est au niveau
 * YADONY_VERIFIED (le seul cas où le claim peut changer l'issue d'un appelant). Ces comptes sont lus par
 * une requête dédiée, jamais dans la liste mixte plafonnée : des déclarants en nombre ne peuvent donc
 * pas masquer le compte validé. Un numéro simplement déclaré ou inconnu ne déclenche aucune lecture.
 * Différence résiduelle : un compte validé par Yadony ajoute une lecture groupée (le cache Caffeine de
 * 5 min de {@link FirebaseContactService} l'atténue sans l'effacer). {@link #findAllByPhone}, réservé à
 * l'admin, lit toujours les candidats.
 *
 * <p>Aucun cache de confiance ici, volontairement : la confiance accordée à un numéro (rattacher un colis,
 * accepter une invitation) est revalidée à chaque usage. Un résultat positif gardé ne serait-ce qu'une
 * minute resterait valable après qu'un propriétaire SMS a rattaché le numéro ou qu'un administrateur a
 * changé la validation.
 */
@Component
public class PhoneLookupService {

    /** Plafond de lignes lues pour l'admin ; couplé au nom de {@link UserRepository#findTop10ByDeclaredPhoneHash}. */
    static final int MAX_CANDIDATES = 10;

    /**
     * Plafond de la lecture des comptes validés par Yadony ; couplé au nom de
     * {@link UserRepository#findTop3ByDeclaredPhoneHashAndDeclaredPhoneLevel}. Deux suffisent pour conclure
     * à l'ambiguïté ; l'index unique partiel de V398 n'en laisse qu'un en PostgreSQL.
     */
    static final int MAX_YADONY_ROWS = 3;

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
     * Numéro déclaré sans SMS que Yadony a validé, quand il est de confiance : niveau YADONY_VERIFIED ET
     * empreinte concordant avec le claim courant (cf. {@link DeclaredPhoneResolver}), ET ce compte est
     * l'unique résultat de {@link #findByPhone} avec la confiance YADONY. Vide dès qu'un propriétaire
     * vérifié par SMS existe ailleurs, que plusieurs comptes validés se disputent le numéro
     * ({@link PhoneMatch.Ambiguous}) ou qu'un compte a un numéro vérifié par SMS (alors seul celui-là
     * compte). Même règle que le chemin aller : aucun rattachement automatique sur un numéro contesté.
     * Jamais mis en cache (voir la Javadoc de classe).
     */
    public Optional<String> trustedDeclaredPhone(UserEntity user, FirebaseContactService.Contact contact) {
        if (user == null) {
            return Optional.empty();
        }
        DeclaredPhoneResolver.Resolved resolved = resolver.resolve(user, contact);
        if (resolved.level() != DeclaredPhoneLevel.YADONY_VERIFIED) {
            return Optional.empty();
        }
        boolean sole = findByPhone(resolved.number()) instanceof PhoneMatch.Found found
                && found.trust() == PhoneMatch.Trust.YADONY
                && found.user() != null && found.user().getId() != null
                && found.user().getId().equals(user.getId());
        return sole ? Optional.of(resolved.number()) : Optional.empty();
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
        // Seul un compte validé par Yadony peut changer l'issue : un simple déclarant n'a jamais d'effet,
        // et sans compte validé aucune lecture Firebase ne part (voir la Javadoc de classe).
        List<UserEntity> rows = yadonyRows(e164);
        if (rows.isEmpty()) {
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
     * par SMS ET les déclarants confirmés (le support doit pouvoir retrouver un squatteur). Les comptes
     * validés par Yadony viennent toujours en premier, puis la lecture mixte ; le tout plafonné à
     * {@value #MAX_CANDIDATES} lignes.
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
        List<UserEntity> rows = adminRows(e164);
        for (PhoneMatch.Found declared : rows.isEmpty() ? List.<PhoneMatch.Found>of() : confirm(rows, e164)) {
            if (all.stream().noneMatch(u -> u.getId() != null && u.getId().equals(declared.user().getId()))) {
                all.add(declared.user());
            }
        }
        return all;
    }

    /** Comptes vivants validés par Yadony sur cette empreinte, lus à part ; aucun appel Firebase. */
    private List<UserEntity> yadonyRows(String e164) {
        if (!hasher.isConfigured()) {
            return List.of();
        }
        return userRepository.findTop3ByDeclaredPhoneHashAndDeclaredPhoneLevel(hasher.hash(e164),
                        DeclaredPhoneLevel.YADONY_VERIFIED).stream()
                .filter(u -> u.getDeletedAt() == null)
                .limit(MAX_YADONY_ROWS)
                .toList();
    }

    /**
     * Comptes validés par Yadony d'abord, puis la lecture mixte (sans ordre), dédoublonnés et plafonnés à
     * {@value #MAX_CANDIDATES} : le plafond de la seconde ne peut pas écarter un compte validé.
     */
    private List<UserEntity> adminRows(String e164) {
        if (!hasher.isConfigured()) {
            return List.of();
        }
        List<UserEntity> ordered = new ArrayList<>(yadonyRows(e164));
        ordered.addAll(userRepository.findTop10ByDeclaredPhoneHash(hasher.hash(e164)));
        List<UserEntity> rows = new ArrayList<>();
        for (UserEntity u : ordered) {
            if (u.getDeletedAt() == null && rows.stream().noneMatch(r -> sameAccount(r, u))) {
                rows.add(u);
            }
        }
        return rows.stream().limit(MAX_CANDIDATES).toList();
    }

    private static boolean sameAccount(UserEntity a, UserEntity b) {
        return a == b || (a.getId() != null && a.getId().equals(b.getId()));
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
