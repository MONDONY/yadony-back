package com.yadony.api.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * Renvoie l'id de la ligne existante pour ce {@code firebaseUid}, ou en crée une
     * (statut actif, aucun rôle) et renvoie son id.
     *
     * <p>À n'appeler que depuis un chemin qui persiste réellement quelque chose pour
     * l'appelant (ex. ajout d'un favori) — jamais depuis un chemin de lecture, sous
     * peine de rompre la matérialisation paresseuse.
     */
    @Transactional
    public UUID resolveOrProvision(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(UserEntity::getId)
                .orElseGet(() -> {
                    UserEntity guest = new UserEntity();
                    guest.setFirebaseUid(firebaseUid);
                    guest.setUsername(usernameGenerator.generate());
                    guest.setStatus(UserStatus.ACTIVE);
                    guest.setKycStatus(KycStatus.NOT_STARTED);
                    return userRepository.save(guest).getId();
                });
    }
}
