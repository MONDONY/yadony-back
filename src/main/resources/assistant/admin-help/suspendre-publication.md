---
id: suspendre-publication
title: Empêcher un utilisateur de publier des trajets ou des colis
category: comptes
order: 42
summary: Suspendre la publication d'un compte sans bloquer son accès, puis lever cette suspension.
permission: USER_SUSPEND
keywords: suspendre la publication, interdire de publier, bloquer les annonces, empêcher de publier un trajet, empêcher de publier un colis, plus de nouvelles annonces, lever la suspension de publication, rétablir la publication, publication suspendue, sanction légère, voyageur problématique, annonces douteuses, actions du compte
routes:
  - path: /users
    label: Chercher un utilisateur
---
## À quoi ça sert

**Suspendre la publication** empêche une personne de publier de nouveaux trajets ou de nouveaux colis, sans bloquer le reste de son compte. Elle peut toujours se connecter, suivre ses envois en cours et échanger. C'est une sanction plus légère que la suspension du compte, utile par exemple pour des annonces douteuses répétées.

## Étapes

### Suspendre la publication

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Cliquez sur **Actions du compte**.
3. Dans la famille **Activité**, cliquez sur **Suspendre la publication** (« Plus de trajets ni de colis publiés »).
4. La fenêtre indique : « L'utilisateur ne pourra plus publier ni trajets ni colis. » Écrivez le **Motif (obligatoire)**.
5. Cliquez sur **Suspendre la publication**.

### Lever la suspension de publication

1. Ouvrez la fiche de la personne.
2. Cliquez sur **Actions du compte**.
3. Cliquez sur **Lever la suspension de publication** (« Autorise de nouveau la publication »).

La levée est immédiate, sans fenêtre de confirmation.

## Comprendre ce que vous voyez

- Le menu montre soit **Suspendre la publication**, soit **Lever la suspension de publication**, selon l'état actuel du compte.
- Les deux gestes apparaissent dans le journal du compte (onglet **Support et journal**) sous **Publication suspendue** et **Publication rétablie**.

## Bon à savoir

- Ce geste ne change pas le statut du compte : il reste **Actif**.
- Les colis et trajets déjà publiés ne sont pas annulés par ce geste. Vérifiez-les dans l'onglet **Colis, trajets et demandes**. Voir [Voir les colis, trajets et demandes d'un utilisateur](/aide/fiche-utilisateur-colis-trajets).
- Pour limiter seulement le nombre de trajets publiés par mois, sans interdire, voir [Limiter le nombre de trajets par mois](/aide/limite-mensuelle-trajets).
- Pour bloquer tout l'accès, voir [Suspendre un compte](/aide/suspendre-reactiver-compte).

## Qui peut le faire

Super admin et Admin (droit de suspendre). Le Support ne peut pas.
