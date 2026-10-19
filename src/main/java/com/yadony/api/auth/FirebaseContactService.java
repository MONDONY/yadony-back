package com.yadony.api.auth;

import com.yadony.api.common.YadonyBusinessException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UidIdentifier;
import com.google.firebase.auth.UserIdentifier;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Source de vérité des coordonnées de contact (téléphone, email) : Firebase Auth,
 * PAS la base Yadony. On ne stocke plus phone/email localement — un vol de la base
 * ne peut donc plus les révéler. Ce service les récupère à la demande via l'Admin
 * SDK, à partir de l'UID Firebase (déjà stocké localement).
 *
 * <p>Un cache mémoire court évite de frapper Firebase à chaque lecture (ex. envoi
 * SMS). Il ne contient que de la PII TRANSITOIRE en RAM (jamais persistée), et est
 * invalidé dès qu'une coordonnée change.
 */
@Service
public class FirebaseContactService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseContactService.class);

    /** Plafond imposé par l'API Firebase {@code getUsers}. */
    private static final int BATCH_SIZE = 100;

    /** Coordonnées de contact d'un utilisateur (peuvent être nulles). */
    public record Contact(String phoneNumber, String email) {
        public static final Contact EMPTY = new Contact(null, null);
    }

    private final FirebaseAuth firebaseAuth;
    private final Cache<String, Contact> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    // required = false : en test/CI, FirebaseConfig ne publie aucun FirebaseAuth
    // (pas de credentials). Le service dégrade alors vers Contact.EMPTY plutôt que
    // d'empêcher le contexte Spring de démarrer.
    public FirebaseContactService(@Autowired(required = false) FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    /** Firebase indisponible (test/CI) : aucune coordonnée n'est lisible ni modifiable. */
    private boolean unavailable() {
        return firebaseAuth == null;
    }

    /**
     * Coordonnées de l'utilisateur pour un UID Firebase. Échec réseau / UID
     * inconnu → {@link Contact#EMPTY} (dégradation gracieuse, jamais d'exception
     * propagée pour ne pas casser un envoi SMS ou une réponse d'API).
     */
    public Contact getContact(String firebaseUid) {
        if (unavailable() || firebaseUid == null || firebaseUid.isBlank()) {
            return Contact.EMPTY;
        }
        return cache.get(firebaseUid, uid -> {
            try {
                UserRecord record = firebaseAuth.getUser(uid);
                return new Contact(record.getPhoneNumber(), record.getEmail());
            } catch (FirebaseAuthException e) {
                log.warn("Firebase getUser({}) a échoué : {}", uid, e.getAuthErrorCode());
                return Contact.EMPTY;
            } catch (Exception e) {
                log.warn("Firebase getUser({}) indisponible", uid, e);
                return Contact.EMPTY;
            }
        });
    }

    /**
     * Comme {@link #getContact}, mais relu dans Firebase en ignorant le cache. Pour les
     * gardes qui bloquent l'utilisateur sur une coordonnée manquante (email requis par
     * Stripe) : l'email ajouté juste avant, par un autre chemin ou sur une autre instance,
     * doit être vu tout de suite, pas cinq minutes plus tard (FLUTTER-BX).
     */
    public Contact getFreshContact(String firebaseUid) {
        if (!unavailable() && firebaseUid != null && !firebaseUid.isBlank()) {
            cache.invalidate(firebaseUid);
        }
        return getContact(firebaseUid);
    }

    /**
     * Coordonnées de plusieurs utilisateurs en un aller-retour (listes admin, exports).
     * Firebase plafonne à 100 identifiants par appel, d'où le découpage.
     *
     * <p>La map renvoyée contient une entrée pour chaque UID demandé, {@link Contact#EMPTY}
     * à défaut : l'appelant peut faire un {@code get} direct sans repli de son côté.
     */
    public Map<String, Contact> getContacts(Collection<String> firebaseUids) {
        Map<String, Contact> result = new HashMap<>();
        if (firebaseUids == null || firebaseUids.isEmpty()) {
            return result;
        }
        // LinkedHashSet : le dédoublonnage est structurel, sans quoi un UID répété
        // compterait deux fois dans le découpage par lots.
        Set<String> missing = new LinkedHashSet<>();
        for (String uid : firebaseUids) {
            if (uid == null || uid.isBlank()) {
                continue;
            }
            Contact cached = unavailable() ? null : cache.getIfPresent(uid);
            if (cached != null) {
                result.put(uid, cached);
            } else {
                missing.add(uid);
            }
        }
        if (!unavailable()) {
            List<String> toFetch = List.copyOf(missing);
            for (int i = 0; i < toFetch.size(); i += BATCH_SIZE) {
                fetchChunk(toFetch.subList(i, Math.min(i + BATCH_SIZE, toFetch.size())), result);
            }
        }
        missing.forEach(uid -> result.putIfAbsent(uid, Contact.EMPTY));
        return result;
    }

    private void fetchChunk(List<String> chunk, Map<String, Contact> result) {
        try {
            List<UserIdentifier> identifiers = chunk.stream()
                    .map(uid -> (UserIdentifier) new UidIdentifier(uid))
                    .toList();
            for (UserRecord record : firebaseAuth.getUsers(identifiers).getUsers()) {
                Contact contact = new Contact(record.getPhoneNumber(), record.getEmail());
                cache.put(record.getUid(), contact);
                result.put(record.getUid(), contact);
            }
            // L'appel a abouti : les UID absents de la réponse n'existent pas côté
            // Firebase. On mémorise cette absence, sinon ils repartiraient dans le lot
            // à chaque rafraîchissement de liste. Rien n'est mémorisé si l'appel échoue
            // (bloc catch) : une panne réseau ne doit pas se figer 5 min en « pas de
            // coordonnées ».
            for (String uid : chunk) {
                if (!result.containsKey(uid)) {
                    cache.put(uid, Contact.EMPTY);
                    result.put(uid, Contact.EMPTY);
                }
            }
        } catch (Exception e) {
            log.warn("Firebase getUsers({} uids) indisponible", chunk.size(), e);
        }
    }

    /**
     * Supprime le compte Firebase et purge le cache dans la foulée. Ce service étant
     * seul à détenir le cache, la suppression lui appartient : laisser l'appelant
     * enchaîner {@code deleteUser} puis {@code evict} laisse la PII en RAM dès qu'un
     * chemin oublie le second appel.
     */
    public DeletionOutcome deleteAccount(String firebaseUid) {
        if (firebaseUid == null) {
            return DeletionOutcome.SKIPPED;
        }
        DeletionOutcome outcome = DeletionOutcome.SKIPPED;
        if (!unavailable()) {
            try {
                firebaseAuth.deleteUser(firebaseUid);
                outcome = DeletionOutcome.DELETED;
            } catch (FirebaseAuthException e) {
                if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                    outcome = DeletionOutcome.ALREADY_GONE;
                } else {
                    log.warn("Firebase deleteUser({}) a échoué : {}", firebaseUid, e.getAuthErrorCode());
                    outcome = DeletionOutcome.FAILED;
                }
            }
        }
        cache.invalidate(firebaseUid);
        return outcome;
    }

    /**
     * Issue d'une suppression de compte Firebase. {@code FAILED} : le compte existe
     * toujours côté Firebase avec son numéro et son email (l'appelant décide d'alerter).
     */
    public enum DeletionOutcome {
        /** Compte supprimé. */
        DELETED,
        /** Firebase ne connaissait déjà plus cet UID. */
        ALREADY_GONE,
        /** Rien à faire (UID nul) ou Firebase non configuré (test/CI). */
        SKIPPED,
        /** Firebase a refusé ou n'a pas répondu : le compte est toujours là. */
        FAILED
    }

    /**
     * Compte Firebase qui porte un numéro : UID, email et fournisseurs de connexion
     * ({@code phone}, {@code google.com}, {@code password}, {@code apple.com}…).
     * Transitoire, jamais persisté ni journalisé (l'email est une PII).
     */
    public record PhoneHolder(String uid, String email, Set<String> providerIds) {
        /**
         * Vrai si ce compte n'a pour toute identité que le numéro : ni email, ni autre
         * fournisseur. C'est le seul cas où un compte Firebase sans compte Yadony actif
         * peut être libéré au profit de celui qui vient de prouver posséder le numéro.
         */
        public boolean phoneOnly() {
            return (email == null || email.isBlank())
                    && providerIds.stream().allMatch("phone"::equals);
        }
    }

    /**
     * Compte Firebase qui porte ce numéro, ou vide si aucun. Une panne Firebase est
     * traitée comme « aucun » (même repli que {@link #findUidByPhone}) : l'écriture qui
     * suit échouera alors proprement si le numéro est en réalité pris.
     */
    public Optional<PhoneHolder> findPhoneHolder(String phoneNumber) {
        if (unavailable() || phoneNumber == null || phoneNumber.isBlank()) {
            return Optional.empty();
        }
        try {
            UserRecord record = firebaseAuth.getUserByPhoneNumber(phoneNumber);
            UserInfo[] providers = record.getProviderData();
            Set<String> providerIds = providers == null ? Set.of()
                    : java.util.Arrays.stream(providers)
                            .map(UserInfo::getProviderId)
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toUnmodifiableSet());
            return Optional.of(new PhoneHolder(record.getUid(), record.getEmail(), providerIds));
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() != AuthErrorCode.USER_NOT_FOUND) {
                log.warn("Firebase getUserByPhoneNumber a échoué : {}", e.getAuthErrorCode());
            }
            return Optional.empty();
        }
    }

    /**
     * Retire le numéro d'un compte Firebase sans le supprimer. Repli quand
     * {@link #deleteAccount} échoue sur un compte fantôme. Vrai si le numéro n'est plus
     * porté par ce compte (retiré, ou compte déjà disparu).
     */
    public boolean clearPhone(String firebaseUid) {
        if (unavailable() || firebaseUid == null) {
            return false;
        }
        try {
            firebaseAuth.updateUser(new UserRecord.UpdateRequest(firebaseUid).setPhoneNumber(null));
            return true;
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                return true;
            }
            log.warn("Firebase updateUser({}) sans numéro a échoué : {}", firebaseUid, e.getAuthErrorCode());
            return false;
        } finally {
            cache.invalidate(firebaseUid);
        }
    }

    /** Vrai si cet email appartient à un compte Firebase autre que {@code selfUid}. */
    public boolean isEmailTakenByAnother(String email, String selfUid) {
        return isTakenByAnother(findUidByEmail(email), selfUid);
    }

    private static boolean isTakenByAnother(Optional<String> foundUid, String selfUid) {
        return foundUid.filter(uid -> !uid.equals(selfUid)).isPresent();
    }

    /** UID Firebase possédant cet email (lookup exact), ou vide si aucun. */
    public Optional<String> findUidByEmail(String email) {
        if (unavailable() || email == null || email.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(firebaseAuth.getUserByEmail(email).getUid());
        } catch (FirebaseAuthException e) {
            return Optional.empty();
        }
    }

    /** UID Firebase possédant ce numéro (lookup exact), ou vide si aucun. */
    public Optional<String> findUidByPhone(String phoneNumber) {
        if (unavailable() || phoneNumber == null || phoneNumber.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(firebaseAuth.getUserByPhoneNumber(phoneNumber).getUid());
        } catch (FirebaseAuthException e) {
            return Optional.empty();
        }
    }

    /**
     * Jeton personnalisé permettant au client de rouvrir sa session sur le même UID.
     *
     * <p>Écrire l'email (ou le numéro) d'un compte via l'Admin SDK est un « changement
     * majeur » pour Firebase : le jeton de rafraîchissement du client est révoqué. La
     * session survit tant que le jeton d'identité en cache est valide (1 h au plus),
     * puis le premier rafraîchissement forcé (appels paiement, KYC, suivi) échoue et le
     * SDK déconnecte l'utilisateur sans prévenir (feedback FLUTTER-4C : « le système
     * m'amène à faire une nouvelle inscription »). Le client s'en sert pour se
     * reconnecter aussitôt, avec un jeton de rafraîchissement neuf.
     *
     * <p>Jamais bloquant : la mutation a déjà réussi. Sans Firebase ou en cas d'échec,
     * renvoie {@code null} et le client garde son comportement antérieur.
     */
    public String issueSessionToken(String firebaseUid) {
        if (unavailable() || firebaseUid == null) {
            return null;
        }
        try {
            return firebaseAuth.createCustomToken(firebaseUid);
        } catch (FirebaseAuthException e) {
            log.warn("Firebase createCustomToken({}) a échoué : {}", firebaseUid, e.getAuthErrorCode());
            return null;
        }
    }

    /** Met à jour l'email côté Firebase (source de vérité) et rafraîchit le cache. */
    public void updateEmail(String firebaseUid, String email) {
        if (unavailable()) {
            return;
        }
        applyUpdate(firebaseUid, new UserRecord.UpdateRequest(firebaseUid).setEmail(email),
                "Mise à jour email Firebase impossible");
    }

    /** Met à jour le téléphone côté Firebase (source de vérité) et rafraîchit le cache. */
    public void updatePhone(String firebaseUid, String phoneNumber) {
        if (unavailable()) {
            return;
        }
        applyUpdate(firebaseUid, new UserRecord.UpdateRequest(firebaseUid).setPhoneNumber(phoneNumber),
                "Mise à jour téléphone Firebase impossible");
    }

    /**
     * Applique une mutation Firebase et réamorce le cache avec le {@link UserRecord}
     * renvoyé, plutôt que d'invalider : tous les appelants relisent les coordonnées
     * dans la foulée (réponse de profil), une invalidation leur coûterait un
     * aller-retour supplémentaire pour une donnée déjà en main.
     */
    private void applyUpdate(String firebaseUid, UserRecord.UpdateRequest request, String errorDetail) {
        try {
            UserRecord updated = firebaseAuth.updateUser(request);
            cache.put(updated.getUid(), new Contact(updated.getPhoneNumber(), updated.getEmail()));
        } catch (FirebaseAuthException e) {
            // Le code d'erreur Firebase est la seule information qui distingue une
            // adresse déjà prise par un autre compte d'une panne de service. Sans
            // cette trace, l'incident ne laissait qu'un « firebase-error » opaque,
            // impossible à diagnostiquer après coup.
            log.error("Firebase updateUser({}) refusé : {} — {}",
                    firebaseUid, e.getAuthErrorCode(), e.getMessage());
            // YadonyBusinessException pour rester dans le contrat RFC 7807 du
            // GlobalExceptionHandler : ces méthodes sont sur des chemins utilisateur
            // (mise à jour de profil, rattachement d'email).
            throw new YadonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "firebase-error", "Firebase Error", errorDetail);
        }
    }

    /** Invalide l'entrée cache (à appeler après toute mutation externe). */
    public void evict(String firebaseUid) {
        if (firebaseUid != null) {
            cache.invalidate(firebaseUid);
        }
    }
}
