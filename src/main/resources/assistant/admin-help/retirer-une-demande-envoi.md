---
id: retirer-une-demande-envoi
title: Retirer ou restaurer une demande d'envoi
category: activite
order: 23
summary: Masquer une demande d'envoi non conforme (les négociations ouvertes sont annulées) ou la rendre de nouveau visible avec Restaurer la demande.
permission: BID_VIEW
keywords: retirer une demande, supprimer une demande d'envoi, masquer une demande, modérer une demande, demande frauduleuse, objet interdit, soupçon de fraude, contenu inapproprié, informations trompeuses, demande en double, non conforme, restaurer une demande, remettre une demande, négociations annulées, retrait impossible, envoi en cours, motif communiqué, note interne, demande signalée, modération
routes:
  - path: /colis?tab=demandes
    label: Voir les demandes d'envoi
---
## À quoi ça sert

Une demande d'envoi publiée par un expéditeur peut être retirée si elle ne respecte pas les règles (objet interdit, arnaque, doublon...). Elle est alors masquée aux voyageurs. Elle peut être restaurée plus tard.

## Étapes

### Retirer une demande

1. Ouvrez **Colis**, onglet **Demandes**. Cochez **Signalées seulement** si vous traitez un signalement.
2. Cliquez sur la demande pour ouvrir sa fiche.
3. Dans la section **Modération**, cliquez sur **Retirer la demande**.
4. Lisez le message de la fenêtre « Retirer cette demande ». Il précise combien de négociations ouvertes seront annulées.
5. Choisissez le **Motif communiqué à l'utilisateur** : **Objet interdit au transport**, **Soupçon de fraude**, **Contenu inapproprié**, **Informations trompeuses ou inexactes**, **Demande en double** ou **Non conforme aux conditions d'utilisation**.
6. Ajoutez si besoin une note interne (facultative, jamais envoyée).
7. Cliquez sur **Retirer**.

### Restaurer une demande

1. Filtrez sur **Retirées** et ouvrez la demande.
2. Cliquez sur **Restaurer la demande**, puis sur **Restaurer** dans la fenêtre de confirmation.

## Comprendre ce que vous voyez

Au retrait :

- la demande est masquée aux voyageurs ;
- seul le motif public est envoyé à l'expéditeur, la note interne reste dans le journal d'audit ;
- les négociations ouvertes sont annulées et les voyageurs concernés sont prévenus.

À la restauration :

- la demande redevient visible avec son statut d'avant le retrait (affiché « Avant retrait : ... » dans la fiche) ;
- les négociations annulées ne reviennent pas : les voyageurs devront refaire une offre.

> **Attention :** les négociations annulées par un retrait sont perdues, même si vous restaurez la demande ensuite.

## Bon à savoir

Le bouton **Retirer la demande** est grisé, avec une explication, quand :

- un envoi est en cours sur cette demande (colis accepté, payé ou en route) : elle ne peut plus être retirée, traitez le problème depuis les litiges (lien **Ouvrir les litiges**) ;
- la demande est déjà retirée ;
- c'est un brouillon jamais publié : il n'y a rien à retirer ;
- la demande est terminée (colis livré).

Si un autre admin a agi en même temps, la fiche se remet à jour avec l'état réel.

## Qui peut le faire

Il faut le droit de retirer du contenu. Les rôles Super admin et Admin l'ont. Pour le rôle Support, la section **Modération** n'apparaît pas.
