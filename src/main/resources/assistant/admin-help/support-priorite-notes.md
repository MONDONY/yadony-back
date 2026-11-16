---
id: support-priorite-notes
title: Changer la priorité d'un ticket et écrire une note interne
category: support
order: 13
summary: Rendre un ticket urgent ou le baisser, et laisser une note visible des seuls agents pour les collègues.
permission: SUPPORT_TICKET_MANAGE
keywords: priorité du ticket, ticket urgent, priorité haute, priorité basse, priorité normale, marquer urgent, remonter un ticket, note interne, commentaire interne, mémo, laisser un mot aux collègues, information pour l'équipe, note invisible pour l'utilisateur, ajouter la note, passation, suivi interne, autres conversations de cette personne, filtre priorité haute
routes:
  - path: /support
    label: Ouvrir le support
---
## À quoi ça sert

La priorité range les tickets dans la file : les tickets **Urgent** et **Haute** remontent en tête du groupe **À répondre**. La note interne sert à garder une trace pour l'équipe (ce qui a été vérifié, ce qu'il reste à faire) sans que l'utilisateur la voie.

## Étapes

### Changer la priorité

1. Ouvrez [Support](/support) et cliquez sur le ticket.
2. Dans la colonne de droite, bloc **Priorité**, cliquez sur **Urgent**, **Haute**, **Normale** ou **Basse**.
3. Le choix est enregistré tout de suite. La pastille de priorité se met à jour dans la file et dans l'en-tête de la conversation.

### Écrire une note interne

1. Ouvrez le ticket.
2. Dans le bloc **Notes internes**, écrivez dans la zone « Ce qu'il faut savoir sur ce ticket… ».
3. Cliquez sur **Ajouter la note**.
4. La note apparaît dans la liste, avec votre e-mail et la date. La zone se vide.

## Comprendre ce que vous voyez

- Dans la file, seules les priorités qui sortent de l'ordinaire s'affichent : **Urgent**, **Priorité haute**, **Priorité basse**. Une priorité normale n'a pas de pastille.
- Le filtre **Priorité haute**, en haut de la file, ne garde que les tickets urgents ou de priorité haute.
- Les notes internes sont « Vues des seuls agents, jamais de l'utilisateur ». Elles ne partent pas dans la conversation.
- **Aucune note.** : personne n'a encore écrit de note sur ce ticket.
- Une note trop courte affiche « Écrivez ce qu'il faut savoir » et le bouton reste grisé. Une note trop longue est aussi refusée.
- Chaque changement de priorité et chaque note sont inscrits dans le bloc **Historique du ticket** (par exemple **Priorité urgente** ou **Note interne ajoutée**), avec l'auteur et la date.

## Bon à savoir

- Contrairement à la réponse, la priorité et les notes ne sont pas réservées à la personne assignée : tout agent qui a le droit de gérer les tickets peut les utiliser, tant que le ticket n'est pas résolu pour la priorité.
- Les notes restent lisibles après la résolution du ticket.
- Le bloc **Autres conversations de cette personne** liste ses autres tickets avec leur statut : un clic ouvre le ticket. Pratique pour voir si le problème a déjà été signalé.
- Si les blocs **Priorité** et **Notes internes** n'apparaissent pas, le serveur ne les propose pas encore ou ils sont en cours de chargement.
- Pour reprendre un ticket ou le résoudre, voir [Résoudre ou reprendre un ticket](/aide/support-resoudre-ticket).

## Qui peut le faire

Lire les notes est possible avec le droit de voir les tickets. Changer la priorité et ajouter une note demandent le droit de gérer les tickets (rôles Super admin, Admin et Support).
