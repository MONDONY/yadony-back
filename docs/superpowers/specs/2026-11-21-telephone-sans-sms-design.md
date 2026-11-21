# Numéro de téléphone sans SMS pour les pays bloqués — design court (v3)

## Pourquoi
Dans certaines zones le SMS de vérification n'arrive pas. Ces pays sont déjà listés dans le paramètre admin `SMS_BLOCKED_COUNTRY_CODES` (aucun SMS n'y part, l'ajout de numéro répond aujourd'hui 503 `country-blocked`). L'utilisateur doit pouvoir enregistrer son numéro quand même.

## Règle (décision du propriétaire)
- L'indicatif du numéro est dans `SMS_BLOCKED_COUNTRY_CODES` : pas de SMS, le numéro est enregistré **déclaré, non vérifié**, et reste **visible** là où un numéro s'affiche, avec la mention « non vérifié ».
- Indicatif hors liste : parcours SMS inchangé.
- Pas de nouveau flag : la liste admin existante est l'interrupteur (vider la liste = tout repasse par SMS).

## Trois niveaux de numéro
1. **Déclaré** : saisi par l'utilisateur, aucune preuve. Modifiable librement.
2. **Vérifié par l'admin** : l'admin le valide à la main (`@PreAuthorize ADMIN`, bouton sur la fiche utilisateur, audit avec l'id de l'admin, sans le numéro). Affiché avec le badge « Vérifié par Yadony » (visible comme vérifié, mais distinct du badge « vérifié par SMS » : autre libellé, autre pastille, et aucun des droits du SMS). Si l'utilisateur modifie ensuite son numéro, retour à « déclaré ».
3. **Vérifié par SMS** : comme aujourd'hui, seul niveau qui sert à se connecter et à payer.

## Rôle du numéro déclaré ou vérifié par l'admin : information seulement
- Sert à **joindre** la personne : l'expéditeur appelle le voyageur, le destinataire appelle le voyageur. Sert aussi à l'admin et au support pour **retrouver le compte** rattaché (recherche admin par numéro).
- **Jamais** pour la connexion (le numéro n'est pas dans le champ Firebase `phoneNumber`, donc aucune connexion par ce numéro, même vérifié par l'admin).
- **Jamais** pour l'argent : aucun paiement, versement, mobile money, remboursement ne le lit.
- **Recherche par numéro : autorisée** pour les numéros déclarés et vérifiés par l'admin, tant que le numéro est rattaché au compte : recherche d'un destinataire (invitation, carnet d'adresses), recherche admin. Une autre session Claude travaille déjà sur la recherche de destinataires par numéro : le constructeur lit sa branche avant de toucher `findUidByPhone` / `RecipientInvitationService` pour ne pas se marcher dessus.
- Mon choix (pas d'unicité des numéros déclarés) : si un numéro correspond à plusieurs comptes, le compte vérifié par SMS gagne ; sinon, aucun rattachement automatique et le client voit « plusieurs comptes », pour éviter de lier un colis au mauvais destinataire.

## Anti-objectifs
- Jamais de badge « téléphone vérifié » sur un numéro déclaré.
- Aucune action automatique sur un numéro déclaré : pas de mobile money, pas de SMS de repli, pas d'identité de connexion. Écrire dans Firebase `phoneNumber` sans code est écarté : ~10 lecteurs le prennent pour vérifié (vol de colis, squat de numéro ; cf. FLUTTER-4H).
- WhatsApp/voix : hors lot.

## Design
- Back : `POST /auth/phone/declare` : 409 si l'indicatif n'est pas dans la liste bloquée (il faut alors le SMS), 409 si un numéro vérifié existe, format E.164, limitation de débit, pas d'unicité. Stockage dans un claim Firebase `declared_phone` (pas de téléphone dans notre base). Audit `USER_PHONE_DECLARED` sans le numéro. Profil : `phoneVerified=false` + numéro déclaré affiché avec la mention.
- Vérifier plus tard par SMS : l'attache habituelle remplace le numéro déclaré (si le pays n'est plus bloqué).
- App : quand l'ajout répond `country-blocked`, l'écran propose « Enregistrer sans vérification » (pas de code), puis affiche « non vérifié ».
- Assistant IA : fiches FR et EN mises à jour dans la PR back. PR back d'abord, app ensuite ; tests ≥ 90 %.

## Garde-fou à tester
Un test par lecteur sensible (connexion, paiements, mobile money, SMS de repli) prouvant qu'il ignore `declared_phone`, quel que soit son niveau.

## Laissé au constructeur
Passer en revue chaque lecteur du numéro : afficher (avec mention) ou ignorer le déclaré ; textes d'écran ; détection côté app (réponse 503 ou liste exposée).

## Mes choix (à corriger)
Claim Firebase plutôt que colonne ; pas d'unicité ; bascule via la réponse `country-blocked`.
