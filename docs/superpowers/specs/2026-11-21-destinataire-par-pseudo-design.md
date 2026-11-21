# Ajouter un destinataire par pseudo Yadony — design

Date : 2026-11-21. Statut : à approuver. Dépôts touchés : `dony-back` (PR à fusionner en premier) puis `dony_app`. Aucun code écrit à ce stade.

## Intention

Aujourd'hui l'expéditeur désigne un destinataire en saisissant son nom et son numéro de téléphone. Le propriétaire veut qu'il puisse aussi désigner un utilisateur déjà inscrit à Yadony avec son **pseudo**, sans que le pseudo révèle le nom, le téléphone ou l'email. Pourquoi : éviter de demander un numéro à quelqu'un qu'on connaît déjà sur l'app, et laisser l'utilisateur donner un identifiant à la place de ses coordonnées.

## Objectifs

1. Chaque utilisateur peut modifier son pseudo (`users.username`) depuis Profil > Modifier le profil, avec vérification de disponibilité et 2 à 3 suggestions si pris.
2. Un expéditeur peut inviter un destinataire par pseudo, en plus du téléphone et de l'email.
3. Le destinataire invité accepte ou refuse. Au refus, l'expéditeur est notifié et le colis reste sans destinataire.
4. Le colis continue pendant l'attente (paiement, trajet). Il est marqué « destinataire en attente ».
5. Aucun appel Firebase pour la recherche par pseudo : une requête SQL sur `users`.

## Hors périmètre

- Recherche floue, autocomplétion, liste de pseudos proches.
- Pseudo visible sur les annonces ou dans la messagerie au-delà de ce qui existe déjà.
- Écran admin de gestion des pseudos (l'admin voit déjà `username`).
- Migration des pseudos existants : `user1785012345` reste valide.
- Changer l'écran de changement de destinataire par nom + téléphone, sauf pour y ajouter le choix « utilisateur Yadony » (voir App).

## Contre-objectifs (échec même si ça marche)

- Un script qui balaie des pseudos obtient la liste des noms des comptes.
- Un destinataire ajouté à un colis sans son accord.
- Un pseudo libéré repris tout de suite pour usurper l'ancien propriétaire.

## Contraintes

- Existant : `users.username` VARCHAR(32), NOT NULL, unique (`ux_users_username`, V183). Généré par `UsernameGenerator` sous la forme `user` + secondes epoch. Aucun endpoint de modification.
- Existant : `POST /recipient-invitations` accepte exactement un de `phone`/`email`, répond toujours `202 SENT` (anti-énumération), quota 20 invitations/24 h, destinataire accepte ou refuse. Seul l'acceptation notifie l'inviteur (`RECIPIENT_INVITATION_ACCEPTED`).
- Existant : refus du colis par le destinataire = `RecipientReplacementService` (demande de remplacement, 12 h entre deux demandes, comptée dans `audit_log`).
- Règles du dépôt : migration V(n+1) sans toucher aux anciennes, soft delete, erreurs RFC 7807, événements Spring entre packages, `audit_log` pour les actions significatives, fiches assistant FR et EN à jour dans la PR back, PR jumelles (back avant app), tests ≥ 90 %.

## Approches considérées

- **A. Ajout direct au carnet sans accord.** Rapide, mais n'importe qui peut ajouter n'importe qui. Écartée.
- **B. Aperçu minimal, puis invitation à accepter (retenue).** Réutilise le système `recipient-invitations` : quota, blocage, audit, notifications, écrans d'acceptation. Coût : l'expéditeur attend la réponse.
- **C. Aperçu, ajout direct, retrait possible par le destinataire.** Plus fluide, mais le destinataire est déjà dans le carnet de l'autre. Écartée.

Choix du propriétaire : **B**, et **le colis continue** pendant l'attente (option 1).

## Décisions

### Règles du pseudo

- Format : 3 à 20 caractères, `a-z`, `0-9`, `_`, `.`. Pas de `.` ni `_` au début ou à la fin, pas de `..`. Les comptes existants (`user1785012345`, 14 à 16 caractères) respectent déjà ce format.
- Unicité insensible à la casse, y compris contre les comptes supprimés (comme `existsByUsername`). Prévoir un index unique sur `lower(username)`.
- Mots réservés refusés : `admin`, `yadony`, `support`, `moderation`, `officiel` et équivalents. Liste de mots interdits dans `common/`.
- Un changement par 30 jours (`users.username_changed_at`).
- L'ancien pseudo est réservé 30 jours après un changement (table `released_usernames`), pour qu'on ne puisse pas le reprendre et se faire passer pour l'ancien propriétaire.

### Vérification et suggestions

- `GET /users/username/availability?value=` (authentifié) renvoie `{available, suggestions[]}`. Suggestions : 2 ou 3 variantes libres du pseudo demandé (suffixe chiffres, `_`), toutes validées contre les mêmes règles.
- Dire « pseudo déjà pris » est voulu par le propriétaire.

### Modification

- `PUT /users/me/username` change le pseudo, vérifie les règles, la fréquence et la course sur l'index unique (réponse 409 propre si l'index tranche). Écrit une ligne `audit_log` (ancien et nouveau pseudo).

### Recherche et aperçu (sans fuite de données)

- `GET /users/lookup?username=` : correspondance exacte uniquement. Introuvable : 404 `problem+json` « Cet utilisateur n'existe pas » (voulu par le propriétaire, ce qui permet de tester l'existence d'un pseudo). Trouvé : `{username, initial}` (initiale du prénom, jamais le nom complet, ni téléphone, ni email).
- Quota : 30 recherches par heure et par utilisateur, 429 au-delà. Nginx garde sa limite par IP.
- Un utilisateur qui a bloqué l'expéditeur (ou inversement) est traité comme introuvable. Se chercher soi-même : 422 « c'est vous ».

### Invitation

- `POST /recipient-invitations` accepte un troisième champ exclusif, `username`, et un `bidId` facultatif. Pour un pseudo, la réponse n'est pas neutre : le pseudo a déjà été validé par `lookup`, l'envoi renvoie `202`.
- `bidId` : colis auquel l'invitation se rapporte. Sans `bidId`, c'est un ajout au carnet comme aujourd'hui.
- La déduplication d'un pseudo se fait par `inviteeUserId` (pas par hash du pseudo, qui peut changer).
- Le quota de 20 invitations/24 h reste commun.

### Colis en attente et refus

- Tant que l'invitation liée à un colis est `PENDING`, le colis est « destinataire en attente » et continue (paiement, trajet, remise avec le code de retrait existant). La relance de 12 h existante s'applique aussi à l'attente.
- Refus : nouvelle notification à l'expéditeur `RECIPIENT_INVITATION_DECLINED` (FR et EN). Si `bidId` : le colis passe sans destinataire, l'expéditeur choisit un autre utilisateur Yadony, un contact ou un numéro (`PUT /bids/{id}/recipient` étendu, ou même événement `BidRecipientChangedEvent`).
- Refus sans `bidId` : seule la notification part, le carnet n'est pas modifié.
- Événements Spring entre `addressbook` et `matching`, jamais d'injection de service d'un package dans l'autre.

### App (dony_app)

- Profil : ligne « Mon identifiant Yadony » avec bouton Copier, éditable dans Modifier le profil, avec vérification en direct (délai de saisie) et suggestions.
- Écran Nouveau destinataire : choix téléphone, email, identifiant. Pour l'identifiant : saisie, aperçu minimal, bouton « Inviter ».
- Colis : état « destinataire en attente » et message au refus avec choix d'un autre destinataire.
- BLoC + GoRouter, aucun `setState`. Boutons dans `stickyBottom` des bottom sheets. Libellés courts, vérifiés en FR et EN à 360 dp. Textes l10n FR et EN.

### Assistant IA

- Mettre à jour dans la PR back, FR et EN (même `id`) : `ecran-nouveau-destinataire`, une fiche « Mon identifiant Yadony », et toute route ajoutée dans `router.dart` ajoutée aux fiches (liste blanche « Y aller »).

### Livraison

- PR back d'abord, puis PR app qui cite la PR back et tolère l'ancien contrat tant que la prod back n'est pas déployée (repli : sans `username`, l'app n'affiche pas le choix par identifiant).
- Pas de déploiement prod sans le propriétaire.

## À régler par le constructeur (mes choix)

- Numéro de migration : prendre V(n+1) libre au moment de coder (collision de version possible entre PR).
- Où vit la table `released_usernames` et son nettoyage (job de purge à 30 jours) : package `auth/`.
- Nom exact des champs et clés l10n.
- Seuils : 30 recherches/heure, 30 jours de réservation. Ajustables par configuration.

## Tranché par le propriétaire

- Remise du colis : **le code de retrait existant reste valable** tant que le destinataire n'a pas accepté (comportement actuel du repli inchangé). « Destinataire en attente » est donc informatif (état, notifications, relance), il ne bloque pas la remise.

## Mon choix à relire

- Aperçu : initiale du prénom + pseudo (jamais le nom complet). À élargir seulement si les testeurs ne reconnaissent pas la bonne personne.
