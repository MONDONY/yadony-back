---
id: moderation
title: "Modération : lire une conversation, supprimer ou rétablir un message"
category: support
order: 40
summary: "La page Modération montre les conversations entre utilisateurs, met en avant celles qui sont signalées, et permet de masquer un message ou d'ajouter une note interne."
permission: MODERATION_VIEW
keywords: modération, modérer, conversation, conversations, messagerie, messages, chat entre utilisateurs, message signalé, conversation signalée, supprimer un message, masquer un message, rétablir un message, annuler la suppression, note interne, insulte, harcèlement, photos de la conversation, participants
routes:
  - path: /moderation
    label: Ouvrir la modération
---
## À quoi ça sert

Les expéditeurs et les voyageurs discutent dans la messagerie de l'application. La page **Modération** (menu **Support et communauté**) permet à l'équipe de lire ces conversations, surtout quand un message a été signalé, et de masquer un message déplacé.

## Comprendre la page

- En haut, une synthèse (**Signalements ouverts**, **Plus ancien signalement**...).
- La **file** des conversations : filtres **Signalées** et **Toutes**, recherche par nom d'un participant. Seules les 100 conversations les plus récentes sont affichées.
- Le **fil** de la conversation au centre : chaque message, avec les étiquettes **Message signalé** ou **Message supprimé**. Les photos s'agrandissent d'un clic.
- Le panneau **Décision** à droite : **Participants** (avec un lien **Fiche**), **Signalement lié**, **Notes internes** et **Historique**.

## Étapes : supprimer un message

1. Ouvrez [Modération](/moderation), filtre **Signalées**.
2. Cliquez sur une conversation et lisez le fil.
3. Sur le message en cause, cliquez sur **Supprimer le message**, indiquez le motif, puis **Supprimer**.
4. Le message est masqué des deux participants. Il reste lisible pour l'équipe.

Pour revenir en arrière : **Annuler la suppression**, puis **Rétablir le message**. Le message redevient visible pour les deux participants.

## Ajouter une note interne

Dans **Notes internes**, écrivez ce qu'il faut savoir sur la conversation, puis **Ajouter la note**. Les notes ne sont vues que par les administrateurs, jamais par les participants.

## Bon à savoir

- Avertir ou suspendre l'auteur d'un message se fait depuis le signalement : cliquez sur **Traiter dans Signalements**. Voir [Signalements](/aide/signalements).
- Une conversation peut être affichée sans signalement : un message peut tout de même être supprimé depuis le fil.

## Qui peut le faire

- Lire les conversations : permission MODERATION_VIEW (Support compris).
- Supprimer ou rétablir un message : permission MESSAGE_DELETE (Support compris).
