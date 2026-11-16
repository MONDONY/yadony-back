---
id: bannir-compte
title: Bannir un compte ou lever le bannissement
category: comptes
order: 41
summary: Exclure définitivement un utilisateur, comprendre les versements retenus, et lever un bannissement si besoin.
permission: USER_BAN
keywords: bannir, bannissement, exclure un utilisateur, interdire un compte, blacklister, compte banni, sanction définitive, fraude, arnaque, lever le bannissement, débannir, réactiver un banni, versements retenus, gains bloqués, argent retenu, paiements retenus, retenue des versements, actions du compte
routes:
  - path: /users
    label: Chercher un utilisateur
  - path: /users?status=BANNED
    label: Voir les comptes bannis
  - path: /transactions?held=true
    label: Voir les versements retenus
---
## À quoi ça sert

**Bannir** exclut définitivement un utilisateur. En plus de bloquer son accès, le bannissement retient ses versements : l'argent qu'il devait recevoir comme voyageur reste chez Yadony. On l'utilise pour une fraude avérée ou un comportement grave.

Pour une mise à l'écart temporaire, préférez [Suspendre un compte](/aide/suspendre-reactiver-compte).

## Étapes

### Bannir

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Cliquez sur **Actions du compte**.
3. Dans la famille **Compte**, cliquez sur **Bannir** (« Définitif : retient aussi les versements »).
4. La fenêtre **Bannir ce compte** indique « Le compte sera banni définitivement ». Écrivez le **Motif (obligatoire)**.
5. Cliquez sur **Bannir**.

Le statut passe à **Banni**. Un bandeau **Versements bloqués** apparaît sur la fiche, avec le nombre de paiements en attente.

### Lever le bannissement

1. Ouvrez la fiche du compte banni (filtre **Bannis** dans [Utilisateurs](/users?status=BANNED)).
2. Cliquez sur **Actions du compte**, puis **Lever le bannissement**.
3. Lisez la fenêtre : « Le compte redeviendra actif : l'utilisateur pourra de nouveau se connecter, publier et échanger. » Si des paiements sont retenus, elle le rappelle.
4. Cliquez sur **Lever le bannissement**. Aucun motif n'est demandé.

## Comprendre ce que vous voyez

- **Versements bloqués depuis le ... (compte banni) : N paiements en attente** : les gains de ce voyageur restent chez Yadony. Chaque paiement se décide à part : rembourser l'expéditeur, ou payer le voyageur par dérogation.
- **Compte rétabli : N paiements toujours retenus, à débloquer manuellement** : le bannissement est levé, mais les paiements bloqués ne repartent pas tout seuls.
- Le lien **Voir les versements retenus** ouvre la page Transactions sur ces paiements.

> **Attention :** lever le bannissement ne débloque pas l'argent. Débloquez chaque paiement depuis Transactions, ou remboursez l'expéditeur.

## Bon à savoir

- **Bannir** n'apparaît que pour un compte **Actif**. Pour bannir un compte suspendu, réactivez-le d'abord.
- Le bannissement ne supprime pas les données du compte. Pour effacer et anonymiser un compte, voir [Supprimer un compte utilisateur](/aide/supprimer-compte).
- Le motif apparaît dans le journal du compte (action **Compte banni**).

## Qui peut le faire

Bannir : Super admin et Admin. Lever le bannissement demande à la fois le droit de bannir et celui de suspendre, donc Super admin et Admin. Le Support ne peut faire ni l'un ni l'autre.
