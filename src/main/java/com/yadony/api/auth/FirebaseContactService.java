package com.yadony.api.auth;

import com.yadony.api.common.YadonyBusinessException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UidIdentifier;
import com.google.firebase.auth.UserIdentifier;
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
    public void deleteAccount(String firebaseUid) {
        if (firebaseUid == null) {
            return;
        }
        if (!unavailable()) {
            try {
                firebaseAuth.deleteUser(firebaseUid);
            } catch (FirebaseAuthException e) {
                log.warn("Firebase deleteUser({}) a échoué : {}", firebaseUid, e.getAuthErrorCode());
            }
        }
        cache.invalidate(firebaseUid);
    }

    /** Vrai si cet email appartient à un compte Firebase autre que {@code selfUid}. */
    public boolean isEmailTakenByAnother(String email, String selfUid) {
        return isTakenByAnother(findUidByEmail(email), selfUid);
    }

    /** Vrai si ce numéro appartient à un compte Firebase autre que {@code selfUid}. */
    public boolean isPhoneTakenByAnother(String phoneNumber, String selfUid) {
        return isTakenByAnother(findUidByPhone(phoneNumber), selfUid);
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
