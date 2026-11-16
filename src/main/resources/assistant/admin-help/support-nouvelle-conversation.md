---
id: support-nouvelle-conversation
title: Écrire à un utilisateur depuis le support
category: support
order: 14
summary: Ouvrir vous-même une conversation support avec un utilisateur, depuis la page Support, sa fiche ou un colis.
permission: SUPPORT_TICKET_MANAGE
keywords: écrire à un utilisateur, contacter un utilisateur, nouvelle conversation, écrire à cet utilisateur, envoyer un message à un client, prendre contact, relancer un utilisateur, message privé, ouvrir un ticket, créer un ticket, contacter l'expéditeur, contacter le voyageur, joindre une image, sujet du message, catégorie, Yadony Support onglet Messages, recontacter après résolution
routes:
  - path: /support
    label: Ouvrir le support
  - path: /users
    label: Chercher un utilisateur
---
## À quoi ça sert

D'habitude, c'est l'utilisateur qui écrit au support. Ce geste permet l'inverse : vous ouvrez une conversation avec une personne, par exemple pour lui demander un document, la prévenir d'un problème sur son colis ou la recontacter après un ticket déjà résolu. Elle reçoit une notification et peut vous répondre.

## Étapes

### Depuis la page Support

1. Ouvrez [Support](/support).
2. En haut de la file, cliquez sur **Nouvelle conversation**. La fenêtre **Écrire à cet utilisateur** s'ouvre.
3. Dans **Destinataire**, cherchez la personne : nom, e-mail, téléphone ou identifiant. Cliquez sur le bon compte dans la liste. **Changer** permet de choisir quelqu'un d'autre.
4. Choisissez la **Catégorie** (Compte, Vérification d'identité, Paiement, Trajet, Colis, Livraison ou Autre).
5. Remplissez le **Sujet (obligatoire)**.
6. Écrivez le **Message** et, si besoin, ajoutez des **Images (facultatif)**.
7. Cliquez sur **Envoyer**.

### Depuis une fiche utilisateur ou un colis

1. Sur la fiche de la personne, cliquez sur **Actions du compte** puis **Écrire à l'utilisateur**. Voir [Lire la fiche complète d'un utilisateur](/aide/fiche-utilisateur).
2. Depuis le détail d'un colis, cliquez sur **Écrire via le support** dans la carte **Expéditeur** ou **Voyageur**.
3. Le destinataire est déjà rempli : il reste la catégorie, le sujet et le message.

Si la page Support est filtrée sur une personne (bandeau **Conversations de ...**), **Nouvelle conversation** vise directement cette personne.

## Comprendre ce que vous voyez

- Un compteur sous le sujet et sous le message indique le nombre de caractères utilisés et le maximum.
- « Écrivez un message ou joignez au moins une image. » : il faut du texte, ou au moins une image.
- Le rappel en bas de la fenêtre : « L'utilisateur recevra une notification et verra ce message dans Yadony Support, onglet Messages. Il pourra vous répondre. »
- Le bouton **Envoyer** reste grisé tant que le destinataire, le sujet et le contenu ne sont pas prêts, ou pendant l'envoi d'une image.

## Bon à savoir

- Après l'envoi, la conversation s'ouvre dans la page Support. Elle vous est déjà assignée et attend la réponse de l'utilisateur : vous n'avez pas besoin de cliquer sur **M'assigner ce ticket**.
- L'historique du ticket indique **Conversation ouverte par le support**.
- On ne peut pas écrire à un compte dont la suppression est engagée : l'option n'apparaît pas dans **Actions du compte**.
- Pour un message court à une seule personne, sans conversation, il existe aussi la notification : voir [Envoyer une notification aux utilisateurs](/aide/communications-envoyer-notification).
- Sans le droit de voir les utilisateurs, la recherche par nom n'est pas proposée : il faut coller l'identifiant du compte.

## Qui peut le faire

Il faut le droit de gérer les tickets du support (rôles Super admin, Admin et Support).
