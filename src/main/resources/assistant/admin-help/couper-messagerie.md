---
id: couper-messagerie
title: Couper ou rétablir la messagerie d'un utilisateur
category: comptes
order: 43
summary: Empêcher un utilisateur d'écrire dans la messagerie pendant 24 heures, 7 jours ou sans limite, puis la rétablir.
permission: USER_MESSAGE_MUTE
keywords: couper la messagerie, mute, rendre muet, bloquer les messages, interdire d'écrire, messages abusifs, harcèlement, insultes, spam, rétablir la messagerie, réactiver la messagerie, durée de la coupure, 24 heures, 7 jours, indéfini, messagerie coupée, chat, discussion, actions du compte
routes:
  - path: /users
    label: Chercher un utilisateur
  - path: /moderation
    label: Ouvrir la Modération
---
## À quoi ça sert

**Couper la messagerie** empêche une personne d'échanger dans la messagerie de l'application, pendant une durée que vous choisissez. Son compte reste utilisable pour le reste. C'est utile face à des messages insultants, du harcèlement ou du spam.

## Étapes

### Couper la messagerie

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Cliquez sur **Actions du compte**.
3. Dans la famille **Activité**, cliquez sur **Couper la messagerie** (« Durée choisie à la confirmation »).
4. Dans la fenêtre, choisissez la **Durée de la coupure** : **24 heures**, **7 jours** ou **Indéfini**.
5. Écrivez le **Motif (obligatoire)**.
6. Cliquez sur **Couper la messagerie**.

### Rétablir la messagerie

1. Ouvrez la fiche de la personne.
2. Cliquez sur **Actions du compte**.
3. Cliquez sur **Rétablir la messagerie** (« Autorise de nouveau les échanges »).

Le rétablissement est immédiat, sans fenêtre de confirmation.

## Comprendre ce que vous voyez

- La fenêtre rappelle la durée choisie, par exemple « La messagerie de cet utilisateur sera coupée pour 7 jours ».
- **Rétablir la messagerie** n'apparaît que si la messagerie est actuellement coupée.
- **Couper la messagerie** reste disponible même si elle est déjà coupée : vous pouvez ainsi changer la durée.
- Les deux gestes apparaissent dans le journal du compte sous **Messagerie coupée** et **Messagerie rétablie**.

## Bon à savoir

- Avec une durée de **24 heures** ou **7 jours**, la messagerie revient seule à la fin de la période. Avec **Indéfini**, elle reste coupée jusqu'à ce qu'un administrateur la rétablisse.
- Pour retirer un message précis, passez par la page [Modération](/moderation).
- Pour une faute plus grave, voir [Suspendre un compte](/aide/suspendre-reactiver-compte).

## Qui peut le faire

Super admin et Admin. Le Support ne peut pas couper la messagerie (il peut en revanche supprimer des messages dans la Modération).
