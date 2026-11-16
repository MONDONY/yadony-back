---
id: support
title: Traiter les tickets du support
category: support
order: 10
summary: "La page Support : la file des tickets des utilisateurs, la conversation au centre, le contexte et les actions à droite."
permission: SUPPORT_TICKET_VIEW
keywords: support, tickets, ticket support, demandes des utilisateurs, file des tickets, messages des utilisateurs, service client, réclamation, à répondre, non assignés, mes tickets, en attente de l'utilisateur, résolus, priorité haute, urgent, statut du ticket, nouveau, attente support, attente utilisateur, conversation support, Yadony Support, catégorie du ticket, plus ancien en attente, sans réponse depuis
routes:
  - path: /support
    label: Ouvrir le support
---
## À quoi ça sert

La page **Support** réunit les demandes que les utilisateurs envoient depuis l'app (rubrique Yadony Support, ou via l'assistant de l'app quand il propose d'écrire à l'équipe). Chaque demande est un ticket : une conversation entre l'utilisateur et l'équipe. La page se lit en trois colonnes : la file à gauche, la conversation au centre, le contexte et les actions à droite.

## Étapes

1. Ouvrez [Support](/support) dans le menu **Support & communauté**.
2. Choisissez un onglet en haut de la file : **Non assignés** (personne ne s'en occupe), **Mes tickets** ou **Tous**. Le chiffre à côté donne le nombre de tickets.
3. Affinez si besoin : tapez un nom, un sujet ou une catégorie dans la recherche, cliquez sur **Priorité haute**, ou sur une catégorie.
4. Cliquez sur un ticket : sa conversation s'affiche au centre.
5. Pour passer d'un ticket à l'autre au clavier, utilisez les touches **J** (suivant) et **K** (précédent).

Pour agir, voir [Répondre à un ticket](/aide/support-repondre-ticket) et [Résoudre ou reprendre un ticket](/aide/support-resoudre-ticket).

## Comprendre ce que vous voyez

### Les quatre compteurs du haut

- **À répondre** : tickets nouveaux ou en attente d'une réponse du support.
- **Non assignés** : tickets ouverts que personne n'a pris.
- **Plus ancien en attente** : depuis combien de temps le ticket le plus ancien attend, avec son sujet.
- **Résolus cette semaine** : tickets terminés ces 7 derniers jours.

### Les groupes de la file

- **À répondre** : c'est au support d'agir. Les tickets urgents et de priorité haute passent en premier, puis le plus ancien.
- **En attente de l'utilisateur** : le support a répondu, on attend la personne.
- **Résolus** : terminés, plus aucune réponse possible.

Sur chaque ligne : le sujet, le nom de la personne, l'aperçu du dernier message (« Support : ... » si c'est l'équipe qui a écrit en dernier), la catégorie, la priorité si elle sort de l'ordinaire, et le responsable (**Moi**, l'e-mail d'un collègue ou **Non assigné**). Le temps écoulé depuis le dernier message passe en orange au bout de 12 h, puis en rouge au bout de 2 jours.

### Les statuts d'un ticket

- **Nouveau** : personne n'a encore répondu.
- **Assigné** : un agent l'a pris en charge.
- **Attente support** : l'utilisateur a écrit, c'est à l'équipe de répondre.
- **Attente utilisateur** : l'équipe a répondu, c'est à la personne de revenir.
- **Résolu** : ticket fermé.

### Les catégories

**Compte**, **Vérification d'identité**, **Paiement**, **Trajet**, **Colis**, **Livraison**, **Autre**.

## Bon à savoir

- La file affiche au plus les 100 conversations les plus récentes. Un message le signale ; la recherche porte sur celles-ci.
- Depuis une fiche utilisateur, la section **Conversations support** montre ses dernières conversations et un lien **Voir tout** qui ouvre la page Support filtrée sur cette personne. Le bandeau **Conversations de ...** s'affiche alors ; cliquez sur **Retirer le filtre** pour revenir à toute la file.
- Un ticket issu d'un signalement porte la mention **Issu du signalement**, avec le lien **Voir le signalement**.
- Un clic sur une notification de nouveau message ouvre directement le bon ticket.

## Qui peut le faire

Il faut le droit de voir les tickets du support. Répondre, s'assigner, résoudre, changer la priorité, écrire une note ou ouvrir une conversation demandent en plus le droit de gérer les tickets. Les rôles Super admin, Admin et Support ont ces deux droits. Les liens **Voir la fiche**, **Ses colis** et **Ses paiements** demandent le droit de voir les utilisateurs.
