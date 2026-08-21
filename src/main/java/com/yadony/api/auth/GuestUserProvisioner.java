package com.yadony.api.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

/**
 * Crée la ligne {@code users} d'un visiteur anonyme, mais seulement au moment
 * où il a réellement quelque chose à conserver.
 *
 * <p>Naviguer ne crée rien : la grande majorité des visiteurs ne laisse aucune
 * trace en base. La ligne n'apparaît qu'au premier favori, ce qui évite
 * d'accumuler des lignes fantômes et réduit d'autant le nettoyage et les
 * fusions à traiter.
 *
 * <p>La ligne naît <b>sans aucun rôle</b> : le statut invité est porté par le
 * token (cf. {@code FirebaseSignInProvider}), et les rôles n'apparaissent qu'à
 * l'inscription réelle. Ajouter un rôle ici créerait une seconde source de
 * vérité pour le statut invité, exactement ce que le design refuse.
 */
@Service
public class GuestUserProvisioner {

    private final UserRepository userRepository;
    private final UsernameGenerator usernameGenerator;

    public GuestUserProvisioner(UserRepository userRepository, UsernameGenerator usernameGenerator) {
        this.userRepository = userRepository;
        this.usernameGenerator = usernameGenerator;
    }

    /**
     * Renvoie l'id de la ligne existante pour ce {@code firebaseUid} (ligne active ou
     * réactivée depuis un soft delete), ou en crée une (statut actif, aucun rôle) et
     * renvoie son id.
     *
     * <p>À n'appeler que depuis un chemin qui persiste réellement quelque chose pour
     * l'appelant (ex. ajout d'un favori) — jamais depuis un chemin de lecture, sous
     * peine de rompre la matérialisation paresseuse.
     */
    @Transactional
    public UUID resolveOrProvision(String firebaseUid) {
        // Ronde de correction 1, constat 1 : rejoindre (propagation REQUIRED) une
        // transaction en lecture seule mettrait Hibernate en FlushMode.MANUAL — l'INSERT
        // ci-dessous ne serait alors jamais émis, et l'appelant recevrait l'UUID d'une
        // ligne qui n'existe pas, sans la moindre erreur. C'est le pire mode de défaillance
        // possible (succès apparent) : on le refuse bruyamment au lieu de le masquer.
        // Ne PAS passer en REQUIRES_NEW pour contourner ceci : cela romprait le rollback
        // conjoint de la ligne invitée avec le favori quand la cible est invalide
        // (cf. FavoriteService.addFavorite).
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "GuestUserProvisioner.resolveOrProvision ne doit jamais être appelé depuis "
                            + "une transaction en lecture seule : l'INSERT serait silencieusement "
                            + "perdu (FlushMode.MANUAL). Provisionner uniquement depuis un chemin "
                            + "d'écriture.");
        }

        return userRepository.findByFirebaseUid(firebaseUid)
                .map(UserEntity::getId)
                .or(() -> reactivateIfSoftDeleted(firebaseUid))
                .orElseGet(() -> provisionNewGuest(firebaseUid));
    }

    /**
     * Une ligne invitée purgée (soft delete, cf. Task 7) reste porteuse du même
     * {@code firebase_uid} : {@link UserRepository#findByFirebaseUid} ne la voit plus
     * (filtre {@code @Where(deleted_at IS NULL)} sur {@link UserEntity}), et une simple
     * réinsertion violerait l'index unique {@code uq_users_firebase_uid}.
     *
     * <p>Même idiome que {@code AuthService#register} : UPDATE natif qui contourne le
     * filtre (au lieu d'un {@code em.merge()} qui SELECT-erait filtré, ne trouverait
     * rien, et tenterait un INSERT en conflit), puis relecture.
     */
    private Optional<UUID> reactivateIfSoftDeleted(String firebaseUid) {
        return userRepository.findByFirebaseUidIncludingDeleted(firebaseUid)
                .map(deleted -> {
                    userRepository.reactivateByFirebaseUid(firebaseUid, UserStatus.ACTIVE.name());
                    return userRepository.findByFirebaseUid(firebaseUid).orElseThrow().getId();
                });
    }

    private UUID provisionNewGuest(String firebaseUid) {
        UserEntity guest = new UserEntity();
        guest.setFirebaseUid(firebaseUid);
        guest.setUsername(usernameGenerator.generate());
        guest.setStatus(UserStatus.ACTIVE);
        guest.setKycStatus(KycStatus.NOT_STARTED);
        try {
            return userRepository.save(guest).getId();
        } catch (DataIntegrityViolationException e) {
            // Ronde de correction 1, constat 2 : check-then-insert non atomique. Deux
            // provisionnements simultanés du même invité (ou une collision de username)
            // violent une contrainte d'unicité ; la ligne existe désormais, on la relit
            // plutôt que de renvoyer un 500. Même idiome que
            // FavoriteService.addFavorite pour l'ajout concurrent d'un favori.
            return userRepository.findByFirebaseUid(firebaseUid)
                    .map(UserEntity::getId)
                    .orElseThrow(() -> e);
        }
    }
}
