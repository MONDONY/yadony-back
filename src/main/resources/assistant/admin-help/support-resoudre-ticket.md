---
id: support-resoudre-ticket
title: Résoudre ou reprendre un ticket du support
category: support
order: 12
summary: Marquer un ticket résolu (définitif) ou reprendre un ticket assigné à un collègue absent.
permission: SUPPORT_TICKET_MANAGE
keywords: résoudre un ticket, marquer résolu, fermer un ticket, clôturer, terminer une demande, ticket résolu, confirmer résoudre ce ticket, reprendre ce ticket, réassigner, collègue absent, changer d'agent, transférer un ticket, ticket bloqué, plus de réponse possible, rouvrir un ticket, historique du ticket, ticket repris, clôture
routes:
  - path: /support
    label: Ouvrir le support
---
## À quoi ça sert

Quand la demande de l'utilisateur est traitée, vous marquez le ticket comme résolu : il quitte la liste des tickets à traiter. Si un collègue a pris un ticket mais ne peut pas le terminer, vous pouvez le reprendre pour pouvoir répondre et le résoudre vous-même.

## Étapes

### Résoudre un ticket

1. Ouvrez [Support](/support) et cliquez sur le ticket. Il doit vous être assigné (badge **Assigné à moi**).
2. Dans la colonne de droite, bloc **Actions**, cliquez sur **Marquer résolu**.
3. Le bouton devient **Confirmer : résoudre ce ticket**. Cliquez dessus pour valider, ou sur **Annuler** pour renoncer.
4. Le ticket passe dans le groupe **Résolus** et le ticket suivant de la file s'ouvre automatiquement.

> **Attention :** la résolution est définitive. Une fois le ticket résolu, plus aucune réponse n'est possible dans cette conversation. La console affiche alors « Ticket résolu le ... : plus de réponse possible. »

Une bonne pratique : envoyer d'abord un dernier message à l'utilisateur, par exemple la réponse type **Clôture**, puis résoudre.

### Reprendre le ticket d'un collègue

1. Ouvrez le ticket assigné à un collègue (son e-mail apparaît dans le badge **Assigné : ...**).
2. Dans le bloc **Actions**, cliquez sur **Reprendre ce ticket**.
3. Le ticket vous est assigné : la zone de réponse et le bouton **Marquer résolu** apparaissent.

## Comprendre ce que vous voyez

- **Répondre et résoudre sont réservés à la personne assignée.** Ce message s'affiche quand le ticket appartient à quelqu'un d'autre.
- Le bloc **Actions** disparaît pour un ticket résolu.
- Le bloc **Historique du ticket**, en bas à droite, retrace tout ce qui s'est passé, du plus ancien au plus récent, avec la date et l'auteur : **Ticket ouvert**, **Ticket assigné**, **Ticket repris**, **Réponse envoyée**, **Image jointe**, **Conversation ouverte par le support**, **Note interne ajoutée**, **Priorité ...** (urgente, haute, normale, basse), **Ticket résolu**, **Masqué par l'utilisateur**.
- **Masqué par l'utilisateur** : la personne a retiré cette conversation de sa liste dans l'app.

## Bon à savoir

- Un ticket résolu ne se rouvre pas depuis la console. Si l'utilisateur a encore besoin d'aide, il peut écrire à nouveau depuis l'app, ou vous pouvez ouvrir une nouvelle conversation avec lui : voir [Écrire à un utilisateur depuis le support](/aide/support-nouvelle-conversation).
- Reprendre un ticket ne demande pas de confirmation, mais le geste est inscrit dans l'historique.
- Pensez à laisser une note interne avant de passer la main, pour que le collègue sache où en est le dossier : voir [Changer la priorité et écrire une note interne](/aide/support-priorite-notes).

## Qui peut le faire

Il faut le droit de gérer les tickets du support (rôles Super admin, Admin et Support).
