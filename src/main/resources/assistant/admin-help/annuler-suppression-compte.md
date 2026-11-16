---
id: annuler-suppression-compte
title: Annuler la suppression demandée par un utilisateur
category: comptes
order: 45
summary: Garder un compte dont la suppression a été demandée mais pas encore exécutée, avec un motif journalisé.
permission: USER_DELETE
keywords: annuler la suppression, garder le compte, conserver un compte, suppression demandée, suppression prévue, rétractation, l'utilisateur a changé d'avis, restaurer un compte, récupérer un compte, compte en cours de suppression, délai de rétractation, réactiver après suppression, statut suppression
routes:
  - path: /users?status=PENDING_DELETION
    label: Voir les suppressions demandées
---
## À quoi ça sert

Quand un utilisateur demande la suppression de son compte, celle-ci n'est pas immédiate. Tant qu'elle n'est pas exécutée, le compte a le statut **Suppression demandée** et un administrateur peut l'annuler, par exemple si la personne a changé d'avis et contacte le support.

## Étapes

1. Ouvrez la page [Utilisateurs](/users?status=PENDING_DELETION) avec le filtre **Suppression demandée**.
2. Cliquez sur la ligne de la personne.
3. Sur la fiche, repérez le bandeau orange **Suppression prévue le ...** (ou **Suppression demandée le ...**).
4. Cliquez sur **Annuler la suppression** dans ce bandeau.
5. La fenêtre **Annuler la suppression du compte** s'ouvre : « Le compte est conservé et redevient actif. » Elle précise aussi que l'utilisateur sera prévenu.
6. Écrivez le **Motif (obligatoire)**. Un compteur indique le nombre de caractères. Le motif doit être assez long : le texte sous le champ vous indique le minimum.
7. Cliquez sur **Annuler la suppression**.

Le bandeau disparaît et le compte repasse à **Actif**.

## Comprendre ce que vous voyez

- Le bandeau rappelle : « L'utilisateur a demandé la suppression de son compte. Tant qu'elle n'est pas exécutée, elle peut être annulée. »
- Le bandeau **À surveiller** affiche aussi « Suppression du compte demandée ».
- Si le motif est refusé (trop court ou trop long), le message s'affiche dans la fenêtre et votre texte est conservé.
- « Annulation indisponible tant que le serveur n'est pas mis à jour » : le geste n'est pas encore possible sur ce serveur. Prévenez l'équipe technique.

## Bon à savoir

- Le motif est enregistré avec votre identifiant (journal du compte, action **Suppression annulée par un administrateur**).
- Une fois la suppression exécutée (depuis [Demandes RGPD](/aide/demandes-rgpd) ou automatiquement), il est trop tard : le compte est anonymisé.
- Pendant la suppression demandée, le menu **Actions du compte** ne propose plus **Écrire à l'utilisateur**.

## Qui peut le faire

Super admin et Admin (droit de supprimer un compte). Le Support voit le bandeau mais pas le bouton.
