---
id: fiche-utilisateur
title: Lire la fiche complète d'un utilisateur
category: comptes
order: 20
summary: "En-tête, alertes, chiffres clés, onglets et menu Actions du compte : tout ce que montre la fiche d'un utilisateur."
permission: USER_VIEW
keywords: fiche utilisateur, profil utilisateur, détail du compte, ouvrir un compte, actions du compte, vue d'ensemble, à surveiller, colis en cours, trajets actifs, argent retenu, litiges ouverts, signalements reçus, note moyenne, copier l'ID, identifiant du compte, badge GP Pro, compte PRO, particulier, professionnel, onglets de la fiche, dernière activité, historique du compte
routes:
  - path: /users
    label: Chercher un utilisateur
---
## À quoi ça sert

La fiche utilisateur rassemble tout ce qu'il faut savoir sur un compte : qui il est, ce qu'il fait en ce moment, son argent, ses incidents, son identité, et les gestes possibles. On l'ouvre en cliquant sur une ligne de la page [Utilisateurs](/users), ou avec le lien **Ouvrir la fiche utilisateur** présent dans d'autres écrans.

## Étapes

1. Ouvrez [Utilisateurs](/users) et recherchez la personne.
2. Cliquez sur sa ligne : la fiche s'ouvre.
3. Lisez l'en-tête et le bandeau **À surveiller**.
4. Cliquez sur un onglet pour voir le détail.
5. Pour agir sur le compte, cliquez sur **Actions du compte** en haut à droite.

## Comprendre ce que vous voyez

### L'en-tête

- Le nom, puis des pastilles : le statut du compte (**Actif**, **Suspendu**, **Banni**, **Suppression demandée**), l'identité (**Identité vérifiée**, **Identité en cours**, **Identité refusée**, **Identité non vérifiée**), le rôle (**Voyageur**, **Expéditeur** ou **Voyageur et expéditeur**).
- Selon le cas : **Particulier** ou **Professionnel** (avec le numéro d'entreprise), **GP Pro** et **Compte PRO**.
- Le téléphone, l'e-mail, la ville et le pays, la date d'inscription.
- L'**ID** du compte avec le bouton **Copier l'ID**, et la **Commission** appliquée (« taux global » ou un taux personnalisé).

### Les bandeaux

- **Versements bloqués** : les gains de ce voyageur restent chez Yadony (compte banni ou identité révoquée). Le lien **Voir les versements retenus** ouvre la liste dans Transactions.
- **Compte rétabli : ... toujours retenus, à débloquer manuellement** : le compte est redevenu normal mais des paiements restent bloqués. Rien ne repart tout seul.
- **Suppression prévue le ...** : la personne a demandé la suppression de son compte. Voir [Annuler une suppression de compte](/aide/annuler-suppression-compte).
- Un bandeau orange signale un voyageur **Particulier** qui publie beaucoup de trajets. Voir [Particuliers à confirmer](/aide/particuliers-a-confirmer).
- **À surveiller** (en rouge) résume les points urgents : compte suspendu ou banni, suppression demandée, litiges ouverts, argent retenu, signalements à traiter.

### Les chiffres clés

Des cartes : **Colis en cours**, **Trajets actifs**, **Argent retenu**, **Litiges ouverts**, **Signalements reçus**, **Note moyenne**. Une carte absente veut dire que vos droits ne permettent pas de la voir.

### Les onglets

- **Vue d'ensemble** : le tableau **En cours et livrés depuis 3 jours** (colis et trajets, avec un lien **Ouvrir**) et la **Dernière activité** (colis, paiements, litiges, signalements).
- **Colis, trajets et demandes** : voir [cet onglet](/aide/fiche-utilisateur-colis-trajets).
- **Argent** : voir [cet onglet](/aide/fiche-utilisateur-argent).
- **Incidents et signalements** : voir [cet onglet](/aide/fiche-utilisateur-incidents).
- **Identité (KYC)** : voir [cet onglet](/aide/fiche-utilisateur-identite).
- **Support et journal** : voir [cet onglet](/aide/fiche-utilisateur-support-journal).
- **Gestion** : voir [cet onglet](/aide/fiche-utilisateur-gestion).

Vous ne voyez que les onglets autorisés par vos droits.

### Le menu Actions du compte

Il regroupe les gestes en trois familles :

- **Communiquer** : **Envoyer une notification** (ouvre la page Communications avec ce compte déjà choisi) et **Écrire à l'utilisateur** (ouvre une conversation support : catégorie, sujet, message, images facultatives, puis **Envoyer**).
- **Compte** : **Suspendre**, **Bannir**, **Réactiver**, **Lever le bannissement**.
- **Activité** : **Suspendre la publication**, **Lever la suspension de publication**, **Couper la messagerie**, **Rétablir la messagerie**.

Seuls les gestes possibles pour ce compte et autorisés pour vous apparaissent.

## Bon à savoir

- « Utilisateur introuvable » s'affiche si le compte n'existe pas : cliquez sur **Retour aux utilisateurs**.
- L'onglet ouvert reste dans l'adresse de la page : un lien partagé rouvre le même onglet.

## Qui peut le faire

Tous les profils peuvent lire la fiche. Le Support ne voit pas l'onglet **Gestion** ni les gestes de sanction (suspendre, bannir, couper la messagerie), mais peut **Écrire à l'utilisateur**.
