---
id: signalements
title: "La page Signalements : traiter les signalements, bugs et suggestions"
category: activite
order: 70
summary: "Les utilisateurs signalent un profil, une annonce, un message, un avis ou un bug. Ouvrez le signalement, choisissez une action, répondez au signalant si besoin."
permission: REPORT_VIEW
keywords: signalements, signaler, signalement, plainte, abus, harcèlement, faux profil, arnaque, objet interdit, spam, contenu inapproprié, bug, bug de l'application, suggestion, retour utilisateur, rejeter un signalement, marquer comme traité, avertir, suspendre la cible, supprimer le message, répondre au signalant, suppression groupée, restaurer, supprimés
routes:
  - path: /signalements
    label: Ouvrir les signalements
---
## À quoi ça sert

Dans l'application, un utilisateur peut **signaler** un autre utilisateur, une annonce, une candidature, une demande d'envoi, un message ou un avis. Il peut aussi envoyer un **bug** ou une **suggestion**. Certains signalements sont créés automatiquement par la plateforme. Tous arrivent dans la page **Signalements** (menu **Activité**), onglet **Signalements**. L'onglet **Avis** sert à modérer les notes : voir [Modérer les avis](/aide/moderer-les-avis).

## Comprendre la page

- Filtres de statut : **Ouverts**, **Résolus**, **Rejetés**, **Tous**, et **Supprimés** pour la corbeille.
- **Type de cible** : Utilisateur, Annonce, Candidature, Message, Avis, Application, Demande d'envoi.
- **Type de retour** : **Bug**, **Avis**, **Suggestion** (ou **Tous retours**).
- Une recherche.

Chaque signalement indique son **motif** : Harcèlement ou comportement abusif, Faux profil, Tentative d'arnaque, Objet interdit au transport, Informations fausses ou trompeuses, Contenu inapproprié, Spam, Problème de paiement, Bug de l'application, Bug signalé depuis un écran, Autre.

Le dossier montre la cible, le contexte (messages, historique, signalements liés) et le panneau **Décision**.

## Étapes : traiter un signalement

1. Ouvrez [Signalements](/signalements), filtre **Ouverts**.
2. Cliquez sur un signalement et lisez le dossier.
3. Choisissez une action. Les actions proposées dépendent de la cible :
   - **Marquer comme traité** : pris en compte, sans action sur la cible (pour un bug : corrigé ou pris en compte).
   - **Rejeter le signalement** : non fondé. Le signalant n'est pas prévenu.
   - **Avertir** ou **Avertir l'auteur** : la personne reçoit un avertissement.
   - **Suspendre la cible** ou **Suspendre l'auteur** : le compte ne peut plus se connecter ni utiliser Yadony.
   - **Retirer le contenu**, **Supprimer le message** (pour les deux participants), **Exclure l'avis de la note**, **Supprimer l'avis**.
4. Ajoutez le motif demandé, puis confirmez.

> **Attention :** suspendre un compte ou supprimer un message a un effet immédiat pour l'utilisateur.

## Répondre au signalant

Sur un bug ou une suggestion, **Répondre au signalant** envoie une notification. Vous pouvez joindre des images. Ne nommez jamais le signalant et ne citez pas la note interne. Le signalement reste ouvert : marquez-le traité quand le bug est corrigé.

## Supprimer ou restaurer en groupe

Cochez plusieurs signalements (ou tous les résultats de la recherche), puis **Supprimer**. Ils sont masqués de l'admin, mais leurs captures et leur historique restent conservés. Le filtre **Supprimés** permet de les retrouver et de les **Restaurer**.

## Qui peut le faire

- Voir : permission REPORT_VIEW. Traiter : REPORT_RESOLVE (Support compris).
- Supprimer des signalements : REPORT_DELETE (Super admin et Admin).
- Certaines actions (suspendre, supprimer un avis) demandent des droits supplémentaires : seules les actions permises à votre rôle sont proposées.
