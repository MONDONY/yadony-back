---
id: supprimer-compte
title: Supprimer définitivement un compte utilisateur
category: comptes
order: 44
summary: "Analyse d'impact, motif, phrase de confirmation : comment supprimer et anonymiser un compte depuis l'onglet Gestion."
permission: USER_DELETE
keywords: supprimer un compte, suppression de compte, effacer un utilisateur, anonymiser, anonymisation, fermer un compte, compte de test, doublon, compte en double, fraude, abus, harcèlement, zone de danger, suppression impossible, paiement en séquestre, portefeuille créditeur, colis en cours, litige ouvert, analyse d'impact, supprimer définitivement
routes:
  - path: /users
    label: Chercher un utilisateur
  - path: /users/rgpd
    label: Ouvrir les demandes RGPD
---
## À quoi ça sert

Ce geste supprime un compte à l'initiative d'un administrateur : le compte est anonymisé (nom, e-mail et données personnelles effacés) et banni. On l'utilise pour un compte de test, un doublon, une fraude, ou une demande faite hors de l'application.

Si la personne a demandé elle-même la suppression depuis l'application, traitez-la plutôt depuis [Demandes RGPD](/aide/demandes-rgpd).

> **Attention :** la suppression est définitive. Elle ne peut pas être annulée.

## Étapes

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Cliquez sur l'onglet **Gestion**.
3. En bas, dans la **Zone de danger**, cliquez sur **Supprimer le compte...**.
4. La fenêtre **Supprimer définitivement ce compte** s'ouvre et affiche « Analyse en cours... ». Attendez le résultat.
5. Lisez les constats (voir plus bas). Si un constat bloque, réglez-le d'abord et revenez plus tard.
6. Choisissez le **Motif** : **Fraude**, **Abus ou harcèlement**, **Compte de test**, **Doublon**, **Demande de l'utilisateur hors RGPD** ou **Autre**.
7. Écrivez le détail du motif dans le champ prévu (obligatoire).
8. Recopiez exactement la phrase demandée dans « Saisissez « ... » pour confirmer ». C'est le prénom et le nom de la personne, ou à défaut son e-mail ou son identifiant.
9. Cliquez sur **Supprimer définitivement**.

Une fois la suppression faite, vous revenez à la liste des utilisateurs.

## Comprendre ce que vous voyez

### Suppression impossible en l'état

Ces constats empêchent la suppression. Le bouton n'apparaît pas tant qu'ils existent :

- **Paiement en séquestre** : de l'argent est bloqué et n'a pas encore été versé. Libérez ou remboursez le paiement.
- **Portefeuille créditeur** : le compte détient encore de l'argent. Déclenchez le remboursement.
- **Remboursement en attente** : une demande de remboursement du portefeuille n'est pas soldée.

### Conséquences sur d'autres comptes

Chaque ligne se déplie et liste les personnes concernées. Cliquer sur un nom ouvre la liste des utilisateurs sur cette personne, dans un nouvel onglet, pour la prévenir.

- **Colis en cours de transport**, **Offre en attente de réponse** (ces offres seront annulées), **Annonce de trajet à venir** (annulées et retirées de la recherche), **Litige ouvert** (tranchez-le de préférence avant), **Conversation active**, **Signalement visant ce compte**, **Signalement écrit par ce compte**.

### Informations complémentaires

Par exemple **Notes données à d'autres comptes** : elles restent affichées, au nom d'un compte anonyme.

## Bon à savoir

- Le détail du motif n'est pas conservé dans le journal : seul le motif choisi dans la liste l'est.
- Si le serveur refuse, le message s'affiche en rouge dans la fenêtre, qui reste ouverte.
- Pour une sanction réversible, voir [Suspendre un compte](/aide/suspendre-reactiver-compte).

## Qui peut le faire

Super admin et Admin. Le Support ne peut pas supprimer de compte.
