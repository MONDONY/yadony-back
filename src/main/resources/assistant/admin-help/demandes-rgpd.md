---
id: demandes-rgpd
title: "Demandes RGPD : exécuter une suppression de compte demandée par l'utilisateur"
category: comptes
order: 70
summary: La file des utilisateurs qui ont demandé la suppression de leur compte. Vous exécutez la suppression avec un motif et une double confirmation.
permission: USER_GDPR_DELETE
keywords: RGPD, demande RGPD, droit à l'oubli, suppression de compte, supprimer mes données, effacer le compte, demande de suppression, exécuter la suppression, anonymiser, anonymisation, données personnelles, file des suppressions, supprimer définitivement, compte supprimé, GDPR
routes:
  - path: /users/rgpd
    label: Ouvrir les demandes RGPD
---
## À quoi ça sert

Un utilisateur peut demander, depuis l'application, la suppression de son compte et de ses données (c'est un droit prévu par le RGPD). Sa demande arrive dans la page **Demandes RGPD** (menu **Comptes**). L'équipe l'exécute depuis cette page.

Cette page ne sert qu'aux demandes reçues. Pour supprimer un compte de votre propre initiative (fraude, doublon...), voir [Supprimer un compte](/aide/supprimer-compte).

## Comprendre ce que vous voyez

Chaque ligne est une demande en attente :

- **Utilisateur** : le prénom et le nom de la personne.
- **Email** : son adresse.
- **Demandée le** : la date de la demande.
- **Ancienneté** : depuis combien de temps la demande attend.

« Aucune demande de suppression en attente » : il n'y a rien à traiter.

## Étapes

1. Ouvrez [Demandes RGPD](/users/rgpd).
2. Avant de supprimer, vérifiez dans la fiche de l'utilisateur qu'aucun colis, paiement ou litige n'est encore en cours. Voir [La fiche utilisateur](/aide/fiche-utilisateur).
3. Sur la ligne de la demande, cliquez sur **Exécuter la suppression**.
4. Écrivez le motif (obligatoire).
5. Recopiez exactement le nom affiché dans le champ « Saisissez ... pour confirmer ».
6. Cliquez sur **Supprimer définitivement**.

> **Attention :** la suppression est irréversible. Le compte est anonymisé et banni : ses informations personnelles sont effacées et la personne ne peut plus se connecter avec ce compte.

## Bon à savoir

- La ligne disparaît de la file une fois la suppression faite.
- L'action est enregistrée dans le journal d'[Audit](/aide/audit).
- Une demande en double ou faite par erreur : contactez l'utilisateur depuis le [Support](/support) avant d'agir.

## Qui peut le faire

Il faut la permission USER_GDPR_DELETE (Super admin et Admin). Le Support n'a pas accès à cette page.
