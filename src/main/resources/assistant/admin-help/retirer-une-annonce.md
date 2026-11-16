---
id: retirer-une-annonce
title: Retirer ou restaurer une annonce de voyageur
category: activite
order: 21
summary: Masquer une annonce non conforme avec un motif envoyé au voyageur et une note interne, ou la rendre de nouveau visible avec Restaurer.
permission: BID_VIEW
keywords: retirer une annonce, supprimer une annonce, masquer une annonce, cacher un trajet, modérer une annonce, annonce frauduleuse, objet interdit, soupçon de fraude, contenu inapproprié, informations trompeuses, annonce en double, non conforme, conditions d'utilisation, restaurer une annonce, remettre une annonce, réactiver une annonce, motif communiqué, note interne, retirée par un admin, modération
routes:
  - path: /colis?tab=announcements
    label: Voir les annonces
---
## À quoi ça sert

Quand une annonce de voyageur pose problème (objet interdit, arnaque, informations fausses, doublon...), vous pouvez la retirer. Elle est alors masquée aux voyageurs et aux expéditeurs. Le geste n'est pas définitif : l'annonce peut être restaurée ensuite.

## Étapes

### Retirer une annonce

1. Ouvrez **Colis**, onglet **Annonces**. Pour l'annonce d'un colis précis, ouvrez plutôt la fiche du colis et cliquez sur **Voir l'annonce**.
2. Sur la ligne de l'annonce, cliquez sur **Retirer**.
3. Dans la fenêtre « Retirer cette annonce », choisissez le **Motif communiqué à l'utilisateur** : **Objet interdit au transport**, **Soupçon de fraude**, **Contenu inapproprié**, **Informations trompeuses ou inexactes**, **Annonce en double** ou **Non conforme aux conditions d'utilisation**.
4. Si vous le souhaitez, ajoutez une note dans le champ « Note interne (facultative, non transmise à l'utilisateur) ».
5. Cliquez sur **Retirer**. Le bouton reste grisé tant qu'aucun motif n'est choisi. **Annuler** ferme la fenêtre sans rien faire.

### Restaurer une annonce

1. Retrouvez l'annonce retirée (statut **REMOVED_BY_ADMIN**) dans l'onglet **Annonces**.
2. Cliquez sur **Restaurer**. L'annonce redevient visible tout de suite, sans fenêtre de confirmation.

## Comprendre ce que vous voyez

- Après un retrait, la ligne se met à jour : le statut passe à **REMOVED_BY_ADMIN** et le bouton devient **Restaurer**.
- Après une restauration, le bouton redevient **Retirer**.
- Pendant l'action, les boutons sont grisés pour éviter un double clic.
- En cas d'échec, un message rouge s'affiche au-dessus du tableau (par exemple « Action échouée »).

## Bon à savoir

- Seul le motif choisi dans la liste est envoyé au voyageur. Ne mettez jamais d'information confidentielle (nom d'un signalant, détails d'enquête) ailleurs que dans la note interne.
- Le texte exact reçu par le voyageur est rédigé par Yadony à partir du motif choisi.
- Avant de retirer une annonce, regardez les colis déjà liés à ce trajet avec **... autres colis sur ce trajet** depuis la fiche d'un colis (voir [Consulter les annonces des voyageurs](/aide/annonces-voyageurs)). Si l'un d'eux doit être arrêté, voir [Annuler un colis](/aide/annuler-un-colis).
- Pour une demande d'envoi publiée par un expéditeur, voir [Retirer ou restaurer une demande d'envoi](/aide/retirer-une-demande-envoi).

## Qui peut le faire

Il faut le droit de retirer du contenu. Les rôles Super admin et Admin l'ont. Le rôle Support ne l'a pas : la colonne des boutons **Retirer** et **Restaurer** ne s'affiche pas pour lui.
