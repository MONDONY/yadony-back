---
id: communications-envoyer-notification
title: Envoyer une notification à un groupe d'utilisateurs ou à une personne
category: support
order: 60
summary: "La page Communications envoie un message aux utilisateurs : à tout le monde, à un groupe construit avec des règles, à un segment enregistré ou à une seule personne."
permission: NOTIFICATION_SEND
keywords: communications, notification, envoyer une notification, push, message à tous, diffusion, newsletter, annonce, campagne, message groupé, cibler des utilisateurs, segment, audience, règles, corridor, voyageurs, expéditeurs, pays, langue, faire sonner les téléphones, boîte Annonces, historique des envois, prévenir les utilisateurs, envoyer à un utilisateur
routes:
  - path: /communications
    label: Ouvrir les communications
---
## À quoi ça sert

La page **Communications** (menu **Support et communauté**) envoie un message dans l'application à des utilisateurs choisis : une nouveauté, une alerte de service, une promotion, ou un message à une seule personne.

## Étapes : envoyer un message

1. Ouvrez [Communications](/communications).
2. Choisissez qui reçoit le message :
   - **Construire une cible** : ajoutez des conditions (pays, langue, activité, date d'inscription, corridor de départ et d'arrivée...). **Toutes les conditions (ET)** ou **Au moins une condition (OU)** dans chaque groupe ; **Ajouter une condition**, **Ajouter un groupe**. Des **Audiences prêtes** et des **Raccourcis** préremplissent les règles.
   - **Segment enregistré** : une cible déjà sauvegardée.
   - **Un utilisateur précis** : cherchez la personne.
3. Cliquez sur **Estimer les destinataires** pour voir combien de comptes recevront le message. **Voir la liste** affiche les destinataires, et **Exporter en CSV** les télécharge.
4. Écrivez le **Titre** et le corps du message. **Utiliser le modèle de message** préremplit un texte quand une audience prête en propose un.
5. Si besoin, cochez **Limiter aux comptes dont l'application est dans cette langue** : le message est écrit dans une seule langue.
6. Cochez **Faire aussi sonner les téléphones** pour une vraie notification. Sans cette case, le message arrive dans la boîte **Annonces** de l'application, sans sonnerie.
7. Cliquez sur **Envoyer** et confirmez.

> **Attention :** un envoi ne peut pas être annulé. Pour une cible sans aucune condition (tous les comptes actifs), la console demande de recopier « DIFFUSER A TOUS ».

## Les segments

Une cible construite peut être sauvegardée avec **Enregistrer comme segment** pour la réutiliser. Dans **Segments enregistrés** : **Renommer**, **Modifier la règle**, **Supprimer**.

## Envoyer à une seule personne depuis sa fiche

Depuis la fiche d'un utilisateur, l'action d'envoi de notification ouvre cette page avec la personne déjà choisie. Si **Ce compte ne recevra pas la notification** s'affiche, la personne ne peut pas être jointe (application désinstallée, notifications coupées...).

## L'historique

Sous le formulaire, la liste des envois passés : **Titre**, **Cible**, **Destinataires**, **Push** et **Envoyé le**.

## Qui peut le faire

Il faut la permission NOTIFICATION_SEND (Super admin et Admin, jamais le Support : un envoi de masse est irréversible).
